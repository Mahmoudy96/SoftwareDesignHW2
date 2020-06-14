package Utils

import java.io.*

//object decleration, The diffrence between class and object is that object does not need a constructor
object Conversion {
    /*
      converts from Byte array to the Any type
      @param input:ByteArray
      @return Any?
     */
    fun fromByteArray(input: ByteArray?): Any? {
        val bis = ByteArrayInputStream(input)
        val inl: ObjectInput = ObjectInputStream(bis)
        return inl.readObject()
    }

    /*
          convert Any type to ByteArray
          @param input:Any?
          @return Any?
         */
    fun toByteArray(input: Any?): Any? {
        val bos = ByteArrayOutputStream()
        val oos = ObjectOutputStream(bos)
        oos.writeObject(input)
        oos.flush()
        return bos.toByteArray()
    }
}