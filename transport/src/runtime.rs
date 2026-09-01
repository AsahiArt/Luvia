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

use std::future::Future;
use std::sync::LazyLock;

use tokio::runtime::Runtime;

use crate::error::TransportError;

static RUNTIME: LazyLock<Runtime> = LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .thread_name("luvia-ssh")
        .worker_threads(2)
        .build()
        .expect("luvia ssh tokio runtime")
});

pub fn runtime() -> &'static Runtime {
    &RUNTIME
}

pub async fn spawn_on_runtime<F, T>(fut: F) -> Result<T, TransportError>
where
    F: Future<Output = T> + Send + 'static,
    T: Send + 'static,
{
    runtime().spawn(fut).await.map_err(|join| {
        if join.is_cancelled() {
            TransportError::disconnected("cancelled")
        } else {
            TransportError::io("internal ssh task failed")
        }
    })
}

pub fn spawn_background<F>(fut: F)
where
    F: Future<Output = ()> + Send + 'static,
{
    drop(runtime().spawn(fut));
}
