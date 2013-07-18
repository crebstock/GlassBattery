## glassbattery

This is an Android application which periodically sends your phone's battery state to Google Glass.  It utilizes the mirror API to do this.

## Dependencies

This app depends on the Google Play Services lib, the jar is included in this project.

## Getting started

First, mad props to twaddington.  His [mirror-quickstart-android][1] sample is the basis of the OAuth portion of this app.  He is smarter than I am, follow the step 2 at his [readme][2] to set up the Glass API on Google's API console.  Ensure that you change the package name of this project to match the one you set up on Google's API console.

Once you've set up the API console, set up this project in your preferred IDE.  

## License

This code is released under the Apache 2.0 license.

[1]: https://github.com/twaddington/mirror-quickstart-android
[2]: https://github.com/twaddington/mirror-quickstart-android/blob/master/README.md#step-2-enable-the-mirror-api