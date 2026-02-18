package ge.mediabox.mediabox.data.remote

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

data class LoginRequest(
    val login: String,
    val password: String
)

data class User(
    val id: String,
    val username: String,
    val email: String,
    val full_name: String?,
    val avatar_url: String?
)

data class LoginResponse(
    val message: String?,
    val access_token: String?,
    val token_type: String?,
    val user: User?,
    val user_id: String?,
    val code: String?
)

data class VerifyRequest(
    val user_id: String,
    val code: String
)

data class VerifyResponse(
    val message: String?,
    val access_token: String?,
    val token: String?, // Adding both just in case
    val status: String?,
    val user: User?
)

data class Plan(
    val id: String,
    val name_ka: String,
    val name_en: String,
    val description_ka: String,
    val description_en: String,
    val price: String,
    val duration_days: Int,
    val is_active: Boolean,
    val created_at: String,
    val updated_at: String
)

interface AuthApiService {
    @Headers("Accept: application/json")
    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @Headers("Accept: application/json")
    @POST("api/auth/login/verify")
    suspend fun verifyLogin(@Body request: VerifyRequest): VerifyResponse

    @Headers("Accept: application/json")
    @GET("api/plans")
    suspend fun getPlans(@Header("Authorization") token: String): List<Plan>

    companion object {
        private const val BASE_URL = "http://159.89.20.100/"

        fun create(): AuthApiService {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

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
