package ge.mediabox.mediabox.data.remote

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

data class LoginRequest(
    val login: String,
    val password: String
)

data class LoginResponse(
    val token: String?,
    val message: String?,
    val status: String?
)

interface AuthApiService {
    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    companion object {
        private const val BASE_URL = "http://159.89.20.100/" // Using the IP from previous snippets if applicable, or as provided

        fun create(): AuthApiService {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            val client = OkHttpClient.Builder()
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
