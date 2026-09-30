Store graphics for this locale.

Expected layout (fastlane supply / F-Droid / IzzyOnDroid):
  icon.png                 512x512
  featureGraphic.png       1024x500 (optional)
  phoneScreenshots/1.png, 2.png, ...
  sevenInchScreenshots/, tenInchScreenshots/  (optional, tablets)

Screenshots will be generated from the preview renderer rather than taken on
a device: ./gradlew :app:updateDebugScreenshotTest renders the @Preview
functions in app/src/screenshotTest/ (phone, landscape, foldable, tablet)
into app/src/screenshotTestDebug/reference/, which is git-ignored. Pick the
phone renders from there, copy them here as 1.png, 2.png, ... and commit
them; tablet renders go to tenInchScreenshots/. Keep the aspect ratio at or
below 2:1. The icon can be exported from docs/logo.svg.

Stores ignore any file here that is not a known graphic, this README included.
