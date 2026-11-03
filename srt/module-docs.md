# Module srt

Coroutine-based Android SRT connections and MPEG-TS publishing/playback.

`SrtConnection` owns a single libsrt 1.5.7 peer and exposes transport state,
messages and statistics. `SrtStream` connects the existing HaishinKit media
pipeline to H.264/H.265 and AAC transport streams. `SrtLogger` configures
process-wide native logging.
