# Bundled ExifTool

Place the ExifTool distribution here so it ships inside the `.app`. The backend resolves the binary
from the `PHOTONIC_EXIFTOOL` environment variable, which the Tauri shell should set to the resolved
resource path at launch (or add a `get_exiftool_path` command). On macOS the simplest option is the
standalone ExifTool build from https://exiftool.org (the `exiftool` executable + `lib/` folder).

If ExifTool is absent the backend still works: it falls back to filesystem metadata and ImageIO
thumbnails for JPEGs (RAW thumbnails/metadata require ExifTool).
