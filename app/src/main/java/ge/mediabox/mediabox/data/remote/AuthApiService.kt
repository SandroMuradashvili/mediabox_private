package ge.mediabox.mediabox.data.remote

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

data class LoginRequest(
    val login: String,
    val password: String
)

data class LoginResponse(
    val message: String?,
    val user_id: String?,
    val code: String?
)

data class VerifyRequest(
    val user_id: String,
    val code: String
)

data class VerifyResponse(
    val message: String?,
    val token: String?,
    val status: String?
)

interface AuthApiService {
    @Headers("Accept: application/json")
    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @Headers("Accept: application/json")
    @POST("api/auth/login/verify")
    suspend fun verifyLogin(@Body request: VerifyRequest): VerifyResponse

    companion object {
        private const val BASE_URL = "http://159.89.20.100/"

        fun create(): AuthApiService {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

            // Interceptor to strip charset=UTF-8 if server is sensitive
            val headerInterceptor = Interceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .method(original.method, original.body)
                    .build()
                chain.proceed(request)
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(headerInterceptor)
                .addInterceptor(logging)
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(AuthApiService::class.java)
        }
    }
}
