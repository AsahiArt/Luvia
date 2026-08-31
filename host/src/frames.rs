use std::io::{self, BufRead, Write};

use crate::error::{Error, Result};

/// UHP and prelude frames are LF-terminated and capped at 1 MiB including LF.
pub const MAX_FRAME_BYTES: usize = 1024 * 1024;

#[derive(Debug, Eq, PartialEq)]
pub enum FrameError {
    Eof,
    MissingLf,
    TooLarge,
    Io,
}

impl FrameError {
    pub fn into_error(self, kind: &str) -> Error {
        match self {
            FrameError::Eof => Error::new("eof", format!("{kind} ended before a frame")),
            FrameError::MissingLf => {
                Error::new("missing_lf", format!("{kind} ended before a terminating LF"))
            }
            FrameError::TooLarge => Error::new(
                "frame_too_large",
                format!("{kind} exceeded the 1 MiB LF frame limit"),
            ),
            FrameError::Io => Error::new("io", format!("failed to read {kind}")),
        }
    }
}

/// Read one LF-terminated frame without allocating beyond [`MAX_FRAME_BYTES`].
pub fn read_frame(reader: &mut impl BufRead) -> std::result::Result<Vec<u8>, FrameError> {
    let mut frame = Vec::new();
    loop {
        let available = reader.fill_buf().map_err(|_| FrameError::Io)?;
        if available.is_empty() {
            return Err(if frame.is_empty() {
                FrameError::Eof
            } else {
                FrameError::MissingLf
            });
        }
        let take = available
            .iter()
            .position(|byte| *byte == b'\n')
            .map_or(available.len(), |position| position + 1);
        if frame.len().saturating_add(take) > MAX_FRAME_BYTES {
            return Err(FrameError::TooLarge);
        }
        frame.extend_from_slice(&available[..take]);
        reader.consume(take);
        if frame.last() == Some(&b'\n') {
            return Ok(frame);
        }
    }
}

pub fn read_text_frame(reader: &mut impl BufRead, kind: &str) -> Result<String> {
    let frame = read_frame(reader).map_err(|error| error.into_error(kind))?;
    let payload = &frame[..frame.len() - 1];
    String::from_utf8(payload.to_vec())
        .map_err(|_| Error::new("invalid_utf8", format!("{kind} is not UTF-8")))
}

pub fn write_frame(writer: &mut impl Write, payload: &[u8]) -> io::Result<()> {
    if payload.len().saturating_add(1) > MAX_FRAME_BYTES {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "frame exceeded the 1 MiB LF frame limit",
        ));
    }
    writer.write_all(payload)?;
    writer.write_all(b"\n")?;
    writer.flush()
}

pub fn write_json_frame(writer: &mut impl Write, value: &serde_json::Value) -> Result<()> {
    let payload = serde_json::to_vec(value)?;
    write_frame(writer, &payload).map_err(Error::from)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn missing_lf_is_rejected() {
        let mut cursor = Cursor::new(b"{\"version\":1,\"operation\":\"discover\"}");
        assert_eq!(read_frame(&mut cursor), Err(FrameError::MissingLf));
    }

    #[test]
    fn empty_input_is_eof() {
        let mut cursor = Cursor::new(b"");
        assert_eq!(read_frame(&mut cursor), Err(FrameError::Eof));
    }

    #[test]
    fn oversized_frame_is_rejected_without_reading_past_cap() {
        let mut oversized = Cursor::new(vec![b'x'; MAX_FRAME_BYTES + 1]);
        assert_eq!(read_frame(&mut oversized), Err(FrameError::TooLarge));
    }

    #[test]
    fn lf_frame_at_limit_is_accepted() {
        let mut data = vec![b'a'; MAX_FRAME_BYTES - 1];
        data.push(b'\n');
        let mut cursor = Cursor::new(data);
        let frame = read_frame(&mut cursor).unwrap();
        assert_eq!(frame.len(), MAX_FRAME_BYTES);
        assert_eq!(frame.last(), Some(&b'\n'));
    }

    #[test]
    fn stops_at_first_lf() {
        let mut cursor = Cursor::new(b"one\ntwo\n");
        assert_eq!(read_frame(&mut cursor).unwrap(), b"one\n");
        assert_eq!(read_frame(&mut cursor).unwrap(), b"two\n");
    }
}
