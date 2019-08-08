package io.supertokens.session

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import java.security.Permission

@Suppress("DeprecatedCallableAddReplaceWith")
class SuperTokensHttpURLConnection(url: String) {
    private val instance = URL(url).openConnection() as HttpURLConnection
    var readTimeout: Int
        get() = instance.readTimeout
        set(value) {
            instance.readTimeout = value
        }


    companion object {
        // UrlConnection methods
         @JvmStatic
         fun getFileNameMap(): FileNameMap {
             return URLConnection.getFileNameMap()
         }

        @JvmStatic
        fun setFileNameMap(map: FileNameMap) {
            URLConnection.setFileNameMap(map)
        }

        @JvmStatic
        fun setDefaultAllowUserInteraction(defaultallowuserinteraction: Boolean) {
            URLConnection.setDefaultAllowUserInteraction(defaultallowuserinteraction)
        }

        @JvmStatic
        fun getDefaultAllowUserInteraction(): Boolean {
            return URLConnection.getDefaultAllowUserInteraction()
        }

        @Deprecated("The instance specific setRequestProperty method should be used after an appropriate instance of URLConnection is obtained. Invoking this method will have no effect.")
        @JvmStatic
        fun setDefaultRequestProperty(key: String, value: String) {
            URLConnection.setDefaultRequestProperty(key, value)
        }

        @Deprecated("The instance specific getRequestProperty method should be used after an appropriate instance of URLConnection is obtained.")
        @JvmStatic
        fun getDefaultRequestProperty(key: String): String {
            return URLConnection.getDefaultRequestProperty(key)
        }

        @JvmStatic
        fun setContentHandlerFactory(fac: ContentHandlerFactory) {
            URLConnection.setContentHandlerFactory(fac)
        }

        @JvmStatic
        fun guessContentTypeFromName(fname: String): String {
            return URLConnection.guessContentTypeFromName(fname)
        }

        @Throws(IOException::class)
        @JvmStatic
        fun guessContentTypeFromStream(ins: InputStream): String? {
            return URLConnection.guessContentTypeFromStream(ins)
        }
        // END URLConnection Methods

        //HttpURLConnection methods

        @JvmStatic
        fun setFollowRedirects(set: Boolean) {
            HttpURLConnection.setFollowRedirects(set)
        }

        @JvmStatic
        fun getFollowRedirects(): Boolean {
            return HttpURLConnection.getFollowRedirects()
        }
    }

    // URLConnection methods
    fun setConnectTimeout(timeout: Int) {
        instance.connectTimeout = timeout
    }

    fun getConnectTimeout(): Int {
        return instance.connectTimeout
    }

//    fun setReadTimeout(timeout: Int) {
//        readTimeout = timeout
////        instance.readTimeout = timeout
//    }

//    fun getReadTimeout(): Int {
////        readTimeout = instance.readTimeout
//        return readTimeout
//    }

    fun getURL(): URL {
        return instance.url
    }

    fun getContentLength(): Int {
        return instance.contentLength
    }

    fun getContentLengthLong(): Long {
        if ( android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N ) {
            return -1
        }
        return instance.contentLengthLong
    }

    fun getContentType(): String? {
        return instance.contentType
    }

    fun getContentEncoding(): String? {
        return instance.contentEncoding
    }

    fun getExpiration(): Long {
        return instance.expiration
    }

    fun getDate(): Long {
        return instance.date
    }

    fun getLastModified(): Long {
        return instance.lastModified
    }

    fun getHeaderField(name: String): String? {
        return instance.getHeaderField(name)
    }

    fun getHeaderFields(): Map<String, List<String>> {
        return instance.headerFields
    }

    fun getHeaderFieldInt(name: String, Default: Int): Int {
        return instance.getHeaderFieldInt(name, Default)
    }

    fun getHeaderFieldLong(name: String, Default: Long): Long {
        if ( android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N ) {
            return -1
        }
        return instance.getHeaderFieldLong(name, Default)
    }

    fun getHeaderFieldKey(n: Int): String? {
        return instance.getHeaderFieldKey(n)
    }

    fun getHeaderField(n: Int): String? {
        return instance.getHeaderField(n)
    }

    @Throws(IOException::class)
    fun getContent(): Any {
        return instance.content
    }

    @Throws(IOException::class)
    fun getContent(classes: Array<Class<*>>): Any {
        return instance.getContent(classes)
    }

    @Throws(IOException::class)
    fun getInputStream(): InputStream {
        return instance.inputStream
    }

    @Throws(IOException::class)
    fun getOutputStream(): OutputStream {
        return instance.outputStream
    }

    override fun toString(): String {
        return instance.toString()
    }

    fun setDoInput(doinput: Boolean) {
        instance.doInput = doinput
    }

    fun getDoInput(): Boolean {
        return instance.doInput
    }

    fun setDoOutput(dooutput: Boolean) {
        instance.doOutput = dooutput
    }

    fun getDoOutput(): Boolean {
        return instance.doOutput
    }

    fun setAllowUserInteraction(allowuserinteraction: Boolean) {
        instance.allowUserInteraction = allowuserinteraction
    }

    fun getAllowUserInteraction(): Boolean {
        return instance.allowUserInteraction
    }

    fun setUseCaches(usecaches: Boolean) {
        instance.useCaches = usecaches
    }

    fun getUseCaches(): Boolean {
        return instance.useCaches
    }

    fun setIfModifiedSince(ifmodifiedsince: Long) {
        instance.ifModifiedSince = ifmodifiedsince
    }

    fun getIfModifiedSince(): Long {
        return instance.ifModifiedSince
    }

    fun getDefaultUseCaches(): Boolean {
        return instance.defaultUseCaches
    }

    fun setDefaultUseCaches(defaultusecaches: Boolean) {
        instance.defaultUseCaches = defaultusecaches
    }

    fun setRequestProperty(key: String?, value: String) {
        instance.setRequestProperty(key, value)
    }

    fun addRequestProperty(key: String?, value: String) {
        instance.addRequestProperty(key, value)
    }

    fun getRequestProperty(key: String): String? {
       return instance.getRequestProperty(key)
    }

    fun getRequestProperties(): Map<String, List<String>> {
        return instance.requestProperties

    }
    // END URLConnection methods

    // HttpUrlConnection methods

    fun setFixedLengthStreamingMode(contentLength: Int) {
        instance.setFixedLengthStreamingMode(contentLength)
    }

    fun setFixedLengthStreamingMode(contentLength: Long) {
        instance.setFixedLengthStreamingMode(contentLength)
    }

    fun setChunkedStreamingMode(chunklen: Int) {
        instance.setChunkedStreamingMode(chunklen)
    }

    fun setInstanceFollowRedirects(followRedirects: Boolean) {
        instance.instanceFollowRedirects = followRedirects
    }

    fun getInstanceFollowRedirects(): Boolean {
        return instance.instanceFollowRedirects
    }

    @Throws(ProtocolException::class)
    fun setRequestMethod(method: String) {
        instance.requestMethod = method
    }

    fun getRequestMethod(): String {
        return instance.requestMethod
    }

    @Throws(IOException::class)
    fun getResponseCode(): Int {
        return instance.responseCode
    }

    @Throws(IOException::class)
    fun getResponseMessage(): String {
        return instance.responseMessage
    }

    fun getHeaderFieldDate(name: String, Default: Long): Long {
        return instance.getHeaderFieldDate(name, Default)
    }

    @Throws(IOException::class)
    fun getPermission(): Permission {
        return instance.permission
    }

    fun getErrorStream(): InputStream? {
        return instance.errorStream
    }
 }