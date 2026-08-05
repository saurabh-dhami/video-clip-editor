# Compose target limitation

`video-clip-editor-compose` deliberately declares `iosArm64` and
`iosSimulatorArm64`, but not `iosX64`.

`iosX64` is a deferred target limitation. The current development host is
Apple Silicon, where `iosX64Test` cannot execute. This does not alter the
shared `video-clip-editor-core` contract. Revisit the target only when an
Intel iOS simulator validation environment is required.
