package id.pdfmaps

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Penyimpanan permanen sederhana: satu file JSON di folder internal app. */
object Storage {
    private fun file(ctx: Context) = File(ctx.filesDir, "store.json")

    fun load(ctx: Context): Store = try {
        val f = file(ctx)
        if (!f.exists()) Store().ensured() else parse(JSONObject(f.readText())).ensured()
    } catch (e: Exception) {
        Store().ensured()
    }

    fun save(ctx: Context, s: Store) {
        try {
            val tmp = File(ctx.filesDir, "store.json.tmp")
            tmp.writeText(toJson(s).toString())
            tmp.copyTo(file(ctx), overwrite = true)
            tmp.delete()
        } catch (_: Exception) {
        }
    }

    private fun ptJson(p: Pt) = JSONArray(
        listOf<Any>(p.lat, p.lon, if (p.ele.isNaN()) JSONObject.NULL else p.ele, p.t)
    )

    private fun toJson(s: Store): JSONObject = JSONObject().apply {
        put("activeLayerId", s.activeLayerId ?: JSONObject.NULL)
        put("coordFormat", s.coordFormat)
        put("lastMapId", s.lastMapId ?: JSONObject.NULL)
        put("bgGeofence", s.bgGeofence)
        put("unit", s.unit)
        put("maps", JSONArray().also { arr ->
            s.maps.forEach { m ->
                arr.put(JSONObject().apply {
                    put("id", m.id); put("name", m.name); put("file", m.file); put("isImage", m.isImage)
                    put("manual", JSONObject().also { mo ->
                        m.manual.forEach { (pg, pts) ->
                            mo.put(pg.toString(), JSONArray().also { pa ->
                                pts.forEach { c -> pa.put(JSONArray(listOf<Any>(c.px, c.py, c.lat, c.lon))) }
                            })
                        }
                    })
                })
            }
        })
        put("layers", JSONArray().also { arr ->
            s.layers.forEach { l ->
                arr.put(JSONObject().apply {
                    put("id", l.id); put("name", l.name); put("visible", l.visible); put("color", l.color)
                    put("fields", JSONArray().also { fa -> l.fields.forEach { fa.put(it) } })
                })
            }
        })
        put("features", JSONArray().also { arr ->
            s.features.forEach { f ->
                arr.put(JSONObject().apply {
                    put("id", f.id); put("layerId", f.layerId); put("type", f.type.name)
                    put("name", f.name); put("notes", f.notes)
                    put("photo", f.photo ?: JSONObject.NULL)
                    put("radius", f.radius); put("created", f.created)
                    put("attrs", JSONObject().also { ao -> f.attrs.forEach { (k, v) -> ao.put(k, v) } })
                    put("pts", JSONArray().also { pa -> f.pts.forEach { pa.put(ptJson(it)) } })
                })
            }
        })
    }

    private fun objs(a: JSONArray?): List<JSONObject> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }

    private fun parse(o: JSONObject): Store {
        val maps = objs(o.optJSONArray("maps")).map { m ->
            val man = mutableMapOf<Int, List<ControlPoint>>()
            val mo = m.optJSONObject("manual")
            if (mo != null) {
                for (k in mo.keys()) {
                    val pa = mo.getJSONArray(k)
                    man[k.toInt()] = (0 until pa.length()).map { j ->
                        val c = pa.getJSONArray(j)
                        ControlPoint(c.getDouble(0), c.getDouble(1), c.getDouble(2), c.getDouble(3))
                    }
                }
            }
            MapEntry(
                id = m.getString("id"), name = m.getString("name"), file = m.getString("file"),
                isImage = m.getBoolean("isImage"), manual = man,
            )
        }
        val layers = objs(o.optJSONArray("layers")).map { l ->
            Layer(
                id = l.getString("id"), name = l.getString("name"),
                visible = l.optBoolean("visible", true), color = l.optLong("color", 0xFFFF9800),
                fields = l.optJSONArray("fields")?.let { fa -> (0 until fa.length()).map { fa.getString(it) } } ?: emptyList(),
            )
        }
        val features = objs(o.optJSONArray("features")).map { f ->
            val pa = f.getJSONArray("pts")
            val pts = (0 until pa.length()).map { j ->
                val c = pa.getJSONArray(j)
                Pt(
                    c.getDouble(0), c.getDouble(1),
                    if (c.isNull(2)) Double.NaN else c.getDouble(2),
                    c.optLong(3, 0L),
                )
            }
            Feature(
                id = f.getString("id"), layerId = f.getString("layerId"),
                type = FType.valueOf(f.getString("type")),
                name = f.getString("name"), notes = f.optString("notes", ""),
                pts = pts,
                photo = if (f.isNull("photo")) null else f.getString("photo"),
                radius = f.optDouble("radius", 0.0), created = f.optLong("created", 0L),
                attrs = f.optJSONObject("attrs")?.let { ao -> ao.keys().asSequence().associateWith { k -> ao.optString(k, "") } } ?: emptyMap(),
            )
        }
        return Store(
            maps = maps, layers = layers, features = features,
            activeLayerId = if (o.isNull("activeLayerId")) null else o.getString("activeLayerId"),
            coordFormat = o.optInt("coordFormat", 0),
            lastMapId = if (o.isNull("lastMapId")) null else o.getString("lastMapId"),
            bgGeofence = o.optBoolean("bgGeofence", false),
            unit = o.optInt("unit", 0).coerceIn(0, 5),
        )
    }
}
