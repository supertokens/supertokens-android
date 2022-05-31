
![SuperTokens banner](https://raw.githubusercontent.com/supertokens/supertokens-logo/master/images/Artboard%20%E2%80%93%2027%402x.png)

# SuperTokens Android SDK

<a href="https://supertokens.io/discord">
<img src="https://img.shields.io/discord/603466164219281420.svg?logo=discord"
    alt="chat on Discord"></a>
    
## About
This is an Android SDK that is responsible for maintaining a SuperTokens session for an Android app.

Learn more at https://supertokens.io

## Installation
1. Add the following to your gradle setup

```gradle
repositories {
    ...
    maven { url 'https://jitpack.io' }
}
```

If you use your project level `build.gradle` file to configure repositories, add the above repository under `allProjects`
If you are using your `settings.gradle` to configure repositories, add the above repository under `dependencyResolutionManagement`

2. Add the dependency

```gradle
implementation 'com.github.supertokens:supertokens-android:0.1.0'
```

To your `build.gradle`(app) file

## Initializing SuperTokens
Initialise SuperTokens using

```java
SuperTokens.init(
    applicationContext,
    /*
    The domain for your API layer, this is used by the SDK when making network requests
    */
    apiDomain,
    
    /*
    (OPTIONAL)
    The base path used when calling SuperTokens.init in the Backend SDK
    /auth by default
    */
    apiBasePath,
    
    /*
    (OPTIONAL)
    The status code used by your API layer to depict session expiration
    401 By default
    */
    sessionExpiredStatusCode,
    
    cookieDomain,
    
    /*
    (OPTIONAL)
    Use this to set custom request headers for network requests made by the SDK
    For example: Sign out, refresh etc
    */
    customHeaderProvider,
    
    /*
    (OPTIONAL)
    Use this to listen to events that the SuperTokens SDK fires over the lifetime of the user
    For example: SESSION_CREATED, SIGN_OUT, ACCESS_TOKEN_PAYLOAD_UPDATED etc
    */
    eventHandler,
)
```

Make sure to call `SuperTokens.init` before using any of the SuperTokens functionality

## Usage
SuperTokens supports:
- HttpURLConnection
- okhttp3
- Retrofit

### Usage with HTTPUrlConnection
1. Set a default cookie store

```java
CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(getApplication()), null));
```

SuperTokens uses cookies for managing sessions. You need to add a default cookie manager before using SuperTokens to make requests.

Note: `SuperTokensPersistentCookieStore` is a cookie store that SuperTokens provides which uses SharedPreferences to persist cookies across app launches

2. Making requests

```java
import com.supertokens.session.SuperTokensHttpURLConnection;
...
// Create a URL object
URL url = new URL("YOUR URL");

// Use SuperTokens to make requests
HttpURLConnection req = SuperTokensHttpURLConnection.newRequest(url, null);

// Add custom request properties when making requests
HttpURLConnection req2 = SuperTokensHttpURLConnection.newRequest(url, new SuperTokensHttpURLConnection.PreConnectCallback() {
    @Override
    public void doAction(HttpURLConnection con) throws IOException {
        // Request method
        con.setRequestMethod("POST");

        // Custom headers
        con.setRequestProperty("header", "value");

        // Request body
        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("property", "value");

        OutputStream outputStream = con.getOutputStream();
        outputStream.write(bodyJson.toString().getBytes(StandardCharsets.UTF_8));
        outputStream.close();
    }
});

req.getResponseCode();
...
```

NOTE: Calling this function with a URL domain that does not match the `apiDomain` you set when calling SuperTokens.init, will result in an Exception.

### Usage with Okhttp/Retrofit

1. Set a cookie jar

```java
OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();
clientBuilder.interceptors().add(new SuperTokensInterceptor());

// Sets persistent cookies
clientBuilder.cookieJar(new PersistentCookieJar(new SetCookieCache(), new SharedPrefsCookiePersistor(context)));

OkHttpClient client = clientBuilder.build();

// RETROFIT ONLY
Retrofit instance = new Retrofit.Builder()
    .baseUrl("YOUR BASE URL")
    .client(client)
    .build();
```

You can use `'com.github.franmontiel:PersistentCookieJar:v1.0.1'` to set the `PersistentCookieJar`

2. Making requests

   You can make requests the same way you normally would. The SuperTokens interceptor will manage sessions for you.

## Additional Functions
1. `SuperTokens.doesSessionExist`

   Returns true/false depending on whether a session currently exists

3. `SuperTokens.signOut`

   Sign the user out

4. `SuperTokens.attemptRefreshingSession`

   Calls the refresh API

5. `SuperTokens.getUserId`

   Returns the user id from the current session

6. `SuperTokens.getAccessTokenPayloadSecurely`

   Returns the data stored in the access token payload

## Contributing
Please refer to the [CONTRIBUTING.md](https://github.com/supertokens/supertokens-android/blob/master/CONTRIBUTING.md) file in this repo.

## Contact us
For any queries, or support requests, please email us at team@supertokens.io, or join our [Discord](supertokens.io/discord) server.

## Authors
Created with :heart: by the folks at SuperTokens.io.
