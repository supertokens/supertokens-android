## SuperTokens Example App

### Add dependencies

This example uses requires the following dependencies in addition to the default dependencies installed when creating an app:
- [supertokens-android](https://github.com/supertokens/supertokens-android)
- [play-services-auth](https://mvnrepository.com/artifact/com.google.android.gms/play-services-auth?repo=google)
- [retrofit](https://square.github.io/retrofit/)
- [AppAuth](https://github.com/openid/AppAuth-Android)

Add the following to your project level `settings.gradle.kts`. Note: This app uses the settings file for dependency resolution but the older method of using the project level `build.gradle` can also be used.

```gradle
dependencyResolutionManagement {
    ...
    repositories {
        ...
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the following to your app level `build.gradle`

```gradle
implementation("com.github.supertokens:supertokens-android:0.5.0")
implementation ("com.google.android.gms:play-services-auth:20.7.0")
implementation("com.squareup.retrofit2:retrofit:2.9.0")
implementation("net.openid:appauth:0.11.1")
```

### Setup

#### Google Login

1. Create OAuth credentials for Android on [Google cloud console](https://console.cloud.google.com/). You will need to add your keystore's SHA-1 fingerprint when creating the credential
2. Create OAuth credentials for Web on [Google cloud console](https://console.cloud.google.com/). This is required because we need to get the authorization code in the Android app to be able to use SuperTokens. You need to provide all values (including domains and URLs) for Google login to work, you can use dummy values if you do not have a web application.
3. Replace all occurences of `GOOGLE_WEB_CLIENT_ID` with the client id for Web in both the Android code and the backend code
4. Replace all occurences of `GOOGLE_WEB_CLIENT_SECRET` with the client secret in the backend code

#### Github login
1. Create credentials for an OAuth app from [Github Developer Settings](https://github.com/settings/developers)
2. Use `com.supertokens.supertokensexample://oauthredirect` when configuring the Authorization callback URL.
3. Replace all occurences of `GITHUB_CLIENT_ID` in both the frontend and backend
4. Replace all occurences of `GITHUB_CLIENT_SECRET` in the backend code
5. If you are using `http://` or `https://` for the callback URL in your Github developer settings you also need to update the `AndroidManifest.xml` to update the scheme for `net.openid.appauth.RedirectUriReceiverActivity`

### Running the app

1. Replace the value of the API domain in `backend/config` and `app/src/main/java/com/supertokens/supertokenxexample/resources/Constants.kt` to match your machines local IP address
2. Navigate to the `/backend` folder and run `npm run start`
3. Run open the app in Android studio and run on a device or emulator

### How it works
- On app launch we check if a session exists and redirect to login if it doesnt
- We add the SuperTokens interceptor to the retrofit client so that the SuperTokens SDK can manage session tokens for us
- After logging in we call APIs exposed by the SuperTokens backend SDKs to create a session and redirect to the home screen
    - In the case of Google we use the id token received after logging in to call the SuperTokens API
    - For Github we use the code and state sent by GitHub to call the SuperTokens API
- On the home screen we call a protected API to fetch session information