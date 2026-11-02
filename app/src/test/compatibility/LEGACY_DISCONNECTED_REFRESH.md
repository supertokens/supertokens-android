# Legacy disconnected refresh risk

For compatibility with 0.5.4, legacy refresh entry points may retry once when a
connection closes without a response. The server may already have consumed and
rotated the refresh token: retrying sends the same token again and can trigger
reuse detection or invalidate the session. A disconnect cannot establish whether
the server processed the request. Strict resolution and SDK-owned OkHttp refresh
do not opt into this retry. Historical caller-provided OkHttp versions and client
settings may independently retry connections; their wire behavior is not controlled
by the SDK. No process-wide JDK networking properties are changed.
