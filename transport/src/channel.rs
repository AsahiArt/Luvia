// Copyright 2026 AsahiArt
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

use std::collections::VecDeque;
use std::sync::Arc;

use bytes::Bytes;
use russh::client;
use russh::{Channel, ChannelMsg};
use tokio::sync::{mpsc, oneshot};

use crate::error::TransportError;
use crate::runtime::spawn_background;

/// Hard cap on a single UniFFI read. Larger values are rejected.
pub const MAX_READ_BYTES: u32 = 1024 * 1024;
const MAX_BUFFER_BYTES: usize = 256 * 1024;

/// One bounded read from a bridge channel.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct ChannelRead {
    pub data: Vec<u8>,
    /// True when the remote side has sent EOF and no further bytes remain.
    pub eof: bool,
}

enum Command {
    Read {
        max: usize,
        reply: oneshot::Sender<Result<ChannelRead, TransportError>>,
    },
    Write {
        data: Vec<u8>,
        reply: oneshot::Sender<Result<(), TransportError>>,
    },
    Close {
        reply: oneshot::Sender<Result<(), TransportError>>,
    },
}

/// Bidirectional exec channel running only `luvia-host bridge`.
#[derive(uniffi::Object)]
pub struct BridgeChannel {
    tx: mpsc::Sender<Command>,
}

impl BridgeChannel {
    pub(crate) fn spawn(channel: Channel<client::Msg>, initial: Vec<u8>) -> Arc<Self> {
        let (tx, rx) = mpsc::channel(8);
        spawn_background(actor_loop(channel, rx, initial));
        Arc::new(Self { tx })
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl BridgeChannel {
    pub async fn read(&self, max_bytes: u32) -> Result<ChannelRead, TransportError> {
        if max_bytes == 0 || max_bytes > MAX_READ_BYTES {
            return Err(TransportError::InvalidReadBound { max_bytes });
        }
        let (reply, rx) = oneshot::channel();
        self.tx
            .send(Command::Read {
                max: max_bytes as usize,
                reply,
            })
            .await
            .map_err(|_| TransportError::ChannelClosed)?;
        rx.await.map_err(|_| TransportError::ChannelClosed)?
    }

    pub async fn write(&self, data: Vec<u8>) -> Result<(), TransportError> {
        if data.is_empty() {
            return Ok(());
        }
        let (reply, rx) = oneshot::channel();
        self.tx
            .send(Command::Write { data, reply })
            .await
            .map_err(|_| TransportError::ChannelClosed)?;
        rx.await.map_err(|_| TransportError::ChannelClosed)?
    }

    pub async fn shutdown(&self) -> Result<(), TransportError> {
        let (reply, rx) = oneshot::channel();
        if self.tx.send(Command::Close { reply }).await.is_err() {
            return Ok(());
        }
        rx.await.unwrap_or(Ok(()))
    }
}

impl Drop for BridgeChannel {
    fn drop(&mut self) {
        let tx = self.tx.clone();
        spawn_background(async move {
            let (reply, rx) = oneshot::channel();
            if tx.send(Command::Close { reply }).await.is_ok() {
                let _ = rx.await;
            }
        });
    }
}

async fn actor_loop(
    mut channel: Channel<client::Msg>,
    mut rx: mpsc::Receiver<Command>,
    initial: Vec<u8>,
) {
    let mut buf = VecDeque::from(initial);
    let mut eof = false;
    let mut closed = false;
    let mut pending_reads: VecDeque<(usize, oneshot::Sender<Result<ChannelRead, TransportError>>)> =
        VecDeque::new();

    loop {
        fulfill_reads(&mut buf, eof, &mut pending_reads);

        if closed {
            break;
        }

        let buf_room = buf.len() < MAX_BUFFER_BYTES;
        tokio::select! {
            cmd = rx.recv() => {
                match cmd {
                    None => {
                        let _ = channel.close().await;
                        closed = true;
                        eof = true;
                    }
                    Some(Command::Read { max, reply }) => {
                        pending_reads.push_back((max, reply));
                    }
                    Some(Command::Write { data, reply }) => {
                        if eof || closed {
                            let _ = reply.send(Err(TransportError::ChannelClosed));
                        } else {
                            let result = channel
                                .data_bytes(Bytes::from(data))
                                .await
                                .map_err(TransportError::from);
                            let _ = reply.send(result);
                        }
                    }
                    Some(Command::Close { reply }) => {
                        if !closed {
                            let _ = channel.close().await;
                            closed = true;
                            eof = true;
                        }
                        let _ = reply.send(Ok(()));
                    }
                }
            }
            msg = channel.wait(), if !eof && buf_room => {
                match msg {
                    None => {
                        eof = true;
                    }
                    Some(ChannelMsg::Data { ref data }) => {
                        buf.extend(data.iter().copied());
                    }
                    Some(ChannelMsg::Eof) | Some(ChannelMsg::Close) => {
                        eof = true;
                    }
                    Some(ChannelMsg::ExitStatus { .. }) => {}
                    _ => {}
                }
            }
        }
    }

    while let Some((_, reply)) = pending_reads.pop_front() {
        let _ = reply.send(Ok(drain_read(&mut buf, usize::MAX, true)));
    }
}

fn fulfill_reads(
    buf: &mut VecDeque<u8>,
    eof: bool,
    pending: &mut VecDeque<(usize, oneshot::Sender<Result<ChannelRead, TransportError>>)>,
) {
    while let Some((max, _)) = pending.front() {
        if buf.is_empty() && !eof {
            break;
        }
        let max = *max;
        let (_, reply) = pending.pop_front().unwrap();
        let _ = reply.send(Ok(drain_read(buf, max, eof)));
    }
}

fn drain_read(buf: &mut VecDeque<u8>, max: usize, eof: bool) -> ChannelRead {
    let take = max.min(buf.len());
    let data: Vec<u8> = buf.drain(..take).collect();
    ChannelRead {
        eof: eof && buf.is_empty(),
        data,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn read_bounds_constant() {
        assert_eq!(MAX_READ_BYTES, 1024 * 1024);
        assert!(MAX_READ_BYTES > 0);
    }
}
