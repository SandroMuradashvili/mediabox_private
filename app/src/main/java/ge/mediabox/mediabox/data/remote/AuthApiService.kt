package ge.mediabox.mediabox.data.remote

import ge.mediabox.mediabox.BuildConfig
import android.content.Context
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST

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

data class MyPlan(
    val plan_id: String,
    val name_en: String,
    val name_ka: String,
    val price: String,
    val expires_at: String,
    val days_left: Double
)

data class Account(
    val id: String,
    val user_id: String,
    val balance: String,
    val created_at: String,
    val updated_at: String
)

data class User(
    val id: String,
    val username: String,
    val full_name: String?,
    val email: String?,
    val phone: String?,
    val avatar_url: String?,
    val role: String,
    val email_verified_at: String?,
    val phone_verified_at: String?,
    val created_at: String,
    val updated_at: String,
    val account: Account?
)

data class PurchaseRequest(val plan_id: String)
data class PurchaseResponse(val message: String?, val success: Boolean = true)

interface AuthApiService {
    @Headers("Accept: application/json")
    @GET("api/plans")
    suspend fun getPlans(): List<Plan>

    @Headers("Accept: application/json")
    @GET("api/plans/my")
    suspend fun getMyPlans(): List<MyPlan>

    @Headers("Accept: application/json")
    @GET("api/user")
    suspend fun getUser(): User

    @Headers("Accept: application/json", "Content-Type: application/json")
    @POST("api/plans/purchase")
    suspend fun purchasePlan(@Body request: PurchaseRequest): PurchaseResponse

    companion object {
        private val BASE_URL = BuildConfig.BASE_URL

        fun create(context: Context): AuthApiService {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

            val headerInterceptor = Interceptor { chain ->
                val original = chain.request()
                val prefs = context.getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE)
                val token = prefs.getString("auth_token", null)

                val request = original.newBuilder()
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .apply {
                        if (!token.isNullOrBlank() && original.header("Authorization").isNullOrBlank()) {
                            header("Authorization", "Bearer $token")
                        }
                    }
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