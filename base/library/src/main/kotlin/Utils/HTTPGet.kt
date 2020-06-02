package Utils
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.result.Result

/**
 * Wrapper class/method for fuels httpGet
 */
class HTTPGet {
    public var connectionSuccess : Boolean = false
    public fun httpGET(requestURL:String, requestParameters:String) : Any {
        connectionSuccess = false
        val (request, response, result) = "$requestURL?$requestParameters".httpGet().response()
        when (result) {
            is Result.Failure -> {
                val ex = result.getException()
                return "connection failure: $ex"
            }
            is Result.Success -> {
                connectionSuccess = true
                return result.get()
            }
        }
    }
}