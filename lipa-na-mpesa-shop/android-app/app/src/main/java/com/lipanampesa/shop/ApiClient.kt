package com.lipanampesa.shop

import android.content.Context
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

interface ApiService {
    @POST("api/auth/login")
    fun login(@Body body: LoginRequest): Call<LoginResponse>

    @POST("api/auth/users")
    fun createUser(@Body body: Map<String, String>): Call<Map<String, Any>>

    @GET("api/shops/me")
    fun getShop(): Call<ShopSettings>

    @PUT("api/shops/me")
    fun putShop(@Body body: Map<String, @JvmSuppressWildcards Any?>): Call<Map<String, Any>>

    @GET("api/items")
    fun items(): Call<List<Item>>

    @GET("api/items/barcode/{code}")
    fun itemByBarcode(@Path("code") code: String): Call<Item>

    @POST("api/items")
    fun createItem(@Body body: ItemBody): Call<Item>

    @PUT("api/items/{id}")
    fun updateItem(@Path("id") id: Int, @Body body: ItemBody): Call<Item>

    @Multipart
    @POST("api/items/import")
    fun importItems(@Part file: MultipartBody.Part): Call<ImportResult>

    @GET("api/transactions")
    fun transactions(): Call<List<Txn>>

    @GET("api/transactions/summary")
    fun summary(): Call<Summary>

    @POST("api/payments/prompt")
    fun prompt(@Body body: PromptRequest): Call<PromptResponse>

    @GET("api/payments/status/{checkout}")
    fun status(@Path("checkout") checkout: String): Call<TxnStatus>
}

object ApiClient {
    fun service(context: Context): ApiService {
        val session = Session(context)
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                session.token?.let { builder.addHeader("Authorization", "Bearer $it") }
                chain.proceed(builder.build())
            }
            .build()
        return Retrofit.Builder()
            .baseUrl(session.baseUrl)
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}

/** Extracts {"error": "..."} from backend error responses. */
fun <T> Response<T>.errorMessage(): String {
    return try {
        val raw = errorBody()?.string() ?: ""
        JSONObject(raw).optString("error").ifEmpty { "HTTP ${code()}" }
    } catch (e: Exception) {
        "HTTP ${code()}"
    }
}

/** Small helper to cut Retrofit callback boilerplate. */
fun <T> Call<T>.go(onOk: (T) -> Unit, onErr: (String) -> Unit) {
    enqueue(object : Callback<T> {
        override fun onResponse(call: Call<T>, response: Response<T>) {
            val body = response.body()
            if (response.isSuccessful && body != null) onOk(body) else onErr(response.errorMessage())
        }

        override fun onFailure(call: Call<T>, t: Throwable) {
            onErr(t.message ?: "Network error")
        }
    })
}
