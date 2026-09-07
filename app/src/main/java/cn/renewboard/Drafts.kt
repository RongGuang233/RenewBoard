package cn.renewboard

import android.content.Context

/** Local unfinished forms, separate from the saved ledger and exported backups. */
internal class DraftStore(context: Context) {
    private val prefs=context.getSharedPreferences("form-drafts",Context.MODE_PRIVATE)
    fun read(key:String):String?=prefs.getString(key,null)
    fun write(key:String,value:String) {prefs.edit().putString(key,value).apply()}
    fun remove(key:String) {prefs.edit().remove(key).apply()}
}
