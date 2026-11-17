package com.proedit.nle.data.local

import com.proedit.nle.domain.model.AudioEffects
import com.proedit.nle.domain.model.BezierControlPoints
import com.proedit.nle.domain.model.Clip
import com.proedit.nle.domain.model.ClipTransform
import com.proedit.nle.domain.model.ColorGrade
import com.proedit.nle.domain.model.ColorWheelValue
import com.proedit.nle.domain.model.CurvePoint
import com.proedit.nle.domain.model.EditMode
import com.proedit.nle.domain.model.EqBand
import com.proedit.nle.domain.model.EqBandType
import com.proedit.nle.domain.model.Keyframe
import com.proedit.nle.domain.model.KeyframeTrack
import com.proedit.nle.domain.model.LutSpec
import com.proedit.nle.domain.model.RgbCurves
import com.proedit.nle.domain.model.RgbOffset
import com.proedit.nle.domain.model.TimelineMarker
import com.proedit.nle.domain.model.TimelineState
import com.proedit.nle.domain.model.Track
import com.proedit.nle.domain.model.TrackType
import com.proedit.nle.domain.model.TransitionSpec
import com.proedit.nle.domain.model.TransitionType
import com.proedit.nle.domain.model.VideoResolution
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lossless JSON codec for persisting and restoring full [TimelineState] graphs in Room.
 */
object TimelineJsonCodec {

    fun encode(state: TimelineState): String {
        val root = JSONObject().apply {
            put("id", state.id)
            put("name", state.name)
            put("fps", state.fps)
            put("isDropFrame", state.isDropFrame)
            put("resolution", JSONObject().apply {
                put("width", state.resolution.width)
                put("height", state.resolution.height)
                put("label", state.resolution.label)
            })
            put("playheadPositionUs", state.playheadPositionUs)
            put("zoomPixelsPerSecond", state.zoomPixelsPerSecond.toDouble())
            put("isMagneticSnapEnabled", state.isMagneticSnapEnabled)
            put("selectedClipId", state.selectedClipId ?: JSONObject.NULL)
            put("selectedTrackId", state.selectedTrackId ?: JSONObject.NULL)
            put("activeEditMode", state.activeEditMode.name)
            put("tracks", JSONArray().apply {
                state.tracks.forEach { put(encodeTrack(it)) }
            })
            put("markers", JSONArray().apply {
                state.markers.forEach { m ->
                    put(JSONObject().apply {
                        put("id", m.id)
                        put("timestampUs", m.timestampUs)
                        put("label", m.label)
                        put("colorHex", m.colorHex)
                    })
                }
            })
        }
        return root.toString()
    }

    fun decode(jsonString: String): TimelineState {
        val root = JSONObject(jsonString)
        val resObj = root.getJSONObject("resolution")
        val resolution = VideoResolution(
            width = resObj.getInt("width"),
            height = resObj.getInt("height"),
            label = resObj.getString("label")
        )

        val tracksArr = root.getJSONArray("tracks")
        val tracks = (0 until tracksArr.length()).map { decodeTrack(tracksArr.getJSONObject(it)) }

        val markersArr = root.optJSONArray("markers") ?: JSONArray()
        val markers = (0 until markersArr.length()).map { i ->
            val m = markersArr.getJSONObject(i)
            TimelineMarker(
                id = m.getString("id"),
                timestampUs = m.getLong("timestampUs"),
                label = m.getString("label"),
                colorHex = m.optLong("colorHex", 0xFFF59E0B)
            )
        }

        val editModeName = root.optString("activeEditMode", EditMode.RIPPLE.name)
        val editMode = runCatching { EditMode.valueOf(editModeName) }.getOrDefault(EditMode.RIPPLE)

        return TimelineState(
            id = root.getString("id"),
            name = root.optString("name", "Untitled Project"),
            fps = root.getDouble("fps"),
            isDropFrame = root.optBoolean("isDropFrame", false),
            resolution = resolution,
            tracks = tracks,
            playheadPositionUs = root.optLong("playheadPositionUs", 0L),
            zoomPixelsPerSecond = root.optDouble("zoomPixelsPerSecond", 96.0).toFloat(),
            isMagneticSnapEnabled = root.optBoolean("isMagneticSnapEnabled", true),
            selectedClipId = root.optString("selectedClipId").takeIf { it.isNotEmpty() && it != "null" },
            selectedTrackId = root.optString("selectedTrackId").takeIf { it.isNotEmpty() && it != "null" },
            activeEditMode = editMode,
            markers = markers
        )
    }

    private fun encodeTrack(track: Track): JSONObject = JSONObject().apply {
        put("id", track.id)
        put("name", track.name)
        put("type", track.type.name)
        put("isMuted", track.isMuted)
        put("isSolo", track.isSolo)
        put("isLocked", track.isLocked)
        put("volumeDb", track.volumeDb.toDouble())
        put("clips", JSONArray().apply {
            track.clips.forEach { put(encodeClip(it)) }
        })
    }

    private fun decodeTrack(obj: JSONObject): Track {
        val clipsArr = obj.getJSONArray("clips")
        return Track(
            id = obj.getString("id"),
            name = obj.getString("name"),
            type = TrackType.valueOf(obj.getString("type")),
            clips = (0 until clipsArr.length()).map { decodeClip(clipsArr.getJSONObject(it)) },
            isMuted = obj.optBoolean("isMuted", false),
            isSolo = obj.optBoolean("isSolo", false),
            isLocked = obj.optBoolean("isLocked", false),
            volumeDb = obj.optDouble("volumeDb", 0.0).toFloat()
        )
    }

    private fun encodeClip(clip: Clip): JSONObject = JSONObject().apply {
        put("id", clip.id)
        put("uri", clip.uri)
        put("name", clip.name)
        put("timelineStartUs", clip.timelineStartUs)
        put("durationUs", clip.durationUs)
        put("sourceStartUs", clip.sourceStartUs)
        put("sourceMaxDurationUs", clip.sourceMaxDurationUs)
        put("audioOffsetUs", clip.audioOffsetUs)
        put("proceduralSeed", clip.proceduralSeed)
        put("transform", JSONObject().apply {
            put("positionX", encodeKeyframeTrack(clip.transform.positionX))
            put("positionY", encodeKeyframeTrack(clip.transform.positionY))
            put("scale", encodeKeyframeTrack(clip.transform.scale))
            put("rotation", encodeKeyframeTrack(clip.transform.rotation))
            put("opacity", encodeKeyframeTrack(clip.transform.opacity))
        })
        put("speedCurve", encodeKeyframeTrack(clip.speedCurve))
        put("colorGrade", encodeColorGrade(clip.colorGrade))
        put("audioEffects", encodeAudioEffects(clip.audioEffects))
        clip.transitionIn?.let { put("transitionIn", encodeTransition(it)) }
        clip.transitionOut?.let { put("transitionOut", encodeTransition(it)) }
    }

    private fun decodeClip(obj: JSONObject): Clip {
        val tObj = obj.getJSONObject("transform")
        val transform = ClipTransform(
            positionX = decodeKeyframeTrack(tObj.getJSONObject("positionX")),
            positionY = decodeKeyframeTrack(tObj.getJSONObject("positionY")),
            scale = decodeKeyframeTrack(tObj.getJSONObject("scale")),
            rotation = decodeKeyframeTrack(tObj.getJSONObject("rotation")),
            opacity = decodeKeyframeTrack(tObj.getJSONObject("opacity"))
        )
        return Clip(
            id = obj.getString("id"),
            uri = obj.getString("uri"),
            name = obj.getString("name"),
            timelineStartUs = obj.getLong("timelineStartUs"),
            durationUs = obj.getLong("durationUs"),
            sourceStartUs = obj.optLong("sourceStartUs", 0L),
            sourceMaxDurationUs = obj.optLong("sourceMaxDurationUs", obj.getLong("durationUs")),
            audioOffsetUs = obj.optLong("audioOffsetUs", 0L),
            transform = transform,
            speedCurve = decodeKeyframeTrack(obj.getJSONObject("speedCurve")),
            colorGrade = decodeColorGrade(obj.getJSONObject("colorGrade")),
            audioEffects = decodeAudioEffects(obj.getJSONObject("audioEffects")),
            transitionIn = obj.optJSONObject("transitionIn")?.let { decodeTransition(it) },
            transitionOut = obj.optJSONObject("transitionOut")?.let { decodeTransition(it) },
            proceduralSeed = obj.optInt("proceduralSeed", 0)
        )
    }

    private fun encodeKeyframeTrack(track: KeyframeTrack<Float>): JSONObject = JSONObject().apply {
        put("defaultValue", track.defaultValue.toDouble())
        put("keyframes", JSONArray().apply {
            track.keyframes.forEach { kf ->
                put(JSONObject().apply {
                    put("id", kf.id)
                    put("timeOffsetUs", kf.timeOffsetUs)
                    put("value", kf.value.toDouble())
                    put("p1x", kf.easing.p1x.toDouble())
                    put("p1y", kf.easing.p1y.toDouble())
                    put("p2x", kf.easing.p2x.toDouble())
                    put("p2y", kf.easing.p2y.toDouble())
                    put("isHold", kf.easing.isHold)
                })
            }
        })
    }

    private fun decodeKeyframeTrack(obj: JSONObject): KeyframeTrack<Float> {
        val arr = obj.getJSONArray("keyframes")
        val kfs = (0 until arr.length()).map { i ->
            val k = arr.getJSONObject(i)
            Keyframe(
                id = k.getString("id"),
                timeOffsetUs = k.getLong("timeOffsetUs"),
                value = k.getDouble("value").toFloat(),
                easing = BezierControlPoints(
                    p1x = k.optDouble("p1x", 0.25).toFloat().coerceIn(0f, 1f),
                    p1y = k.optDouble("p1y", 0.25).toFloat(),
                    p2x = k.optDouble("p2x", 0.75).toFloat().coerceIn(0f, 1f),
                    p2y = k.optDouble("p2y", 0.75).toFloat(),
                    isHold = k.optBoolean("isHold", false)
                )
            )
        }
        return KeyframeTrack(
            defaultValue = obj.getDouble("defaultValue").toFloat(),
            keyframes = kfs
        )
    }

    private fun encodeColorGrade(grade: ColorGrade): JSONObject = JSONObject().apply {
        put("lift", encodeWheel(grade.lift))
        put("gamma", encodeWheel(grade.gamma))
        put("gain", encodeWheel(grade.gain))
        put("offsetR", grade.offset.r.toDouble())
        put("offsetG", grade.offset.g.toDouble())
        put("offsetB", grade.offset.b.toDouble())
        put("temperature", grade.temperature.toDouble())
        put("tint", grade.tint.toDouble())
        put("contrast", grade.contrast.toDouble())
        put("pivot", grade.pivot.toDouble())
        put("saturation", grade.saturation.toDouble())
        put("vibrance", grade.vibrance.toDouble())
        put("highlights", grade.highlights.toDouble())
        put("shadows", grade.shadows.toDouble())
        put("isBypassed", grade.isBypassed)
        put("curves", JSONObject().apply {
            put("master", encodeCurvePoints(grade.rgbCurves.master))
            put("red", encodeCurvePoints(grade.rgbCurves.red))
            put("green", encodeCurvePoints(grade.rgbCurves.green))
            put("blue", encodeCurvePoints(grade.rgbCurves.blue))
        })
        grade.lut?.let { lut ->
            put("lut", JSONObject().apply {
                put("name", lut.name)
                put("cubeContent", lut.cubeContent)
                put("intensity", lut.intensity.toDouble())
            })
        }
    }

    private fun decodeColorGrade(obj: JSONObject): ColorGrade {
        val curvesObj = obj.optJSONObject("curves")
        val curves = if (curvesObj != null) {
            RgbCurves(
                master = decodeCurvePoints(curvesObj.getJSONArray("master")),
                red = decodeCurvePoints(curvesObj.getJSONArray("red")),
                green = decodeCurvePoints(curvesObj.getJSONArray("green")),
                blue = decodeCurvePoints(curvesObj.getJSONArray("blue"))
            )
        } else RgbCurves()

        val lutObj = obj.optJSONObject("lut")
        val lut = if (lutObj != null) {
            LutSpec(
                name = lutObj.getString("name"),
                cubeContent = lutObj.getString("cubeContent"),
                intensity = lutObj.optDouble("intensity", 1.0).toFloat()
            )
        } else null

        return ColorGrade(
            lift = decodeWheel(obj.getJSONObject("lift")),
            gamma = decodeWheel(obj.getJSONObject("gamma")),
            gain = decodeWheel(obj.getJSONObject("gain")),
            offset = RgbOffset(
                r = obj.optDouble("offsetR", 0.0).toFloat(),
                g = obj.optDouble("offsetG", 0.0).toFloat(),
                b = obj.optDouble("offsetB", 0.0).toFloat()
            ),
            temperature = obj.optDouble("temperature", 0.0).toFloat(),
            tint = obj.optDouble("tint", 0.0).toFloat(),
            contrast = obj.optDouble("contrast", 1.0).toFloat(),
            pivot = obj.optDouble("pivot", 0.435).toFloat(),
            saturation = obj.optDouble("saturation", 1.0).toFloat(),
            vibrance = obj.optDouble("vibrance", 0.0).toFloat(),
            highlights = obj.optDouble("highlights", 0.0).toFloat(),
            shadows = obj.optDouble("shadows", 0.0).toFloat(),
            rgbCurves = curves,
            lut = lut,
            isBypassed = obj.optBoolean("isBypassed", false)
        )
    }

    private fun encodeWheel(w: ColorWheelValue): JSONObject = JSONObject().apply {
        put("r", w.r.toDouble())
        put("g", w.g.toDouble())
        put("b", w.b.toDouble())
        put("master", w.master.toDouble())
    }

    private fun decodeWheel(obj: JSONObject): ColorWheelValue = ColorWheelValue(
        r = obj.getDouble("r").toFloat(),
        g = obj.getDouble("g").toFloat(),
        b = obj.getDouble("b").toFloat(),
        master = obj.getDouble("master").toFloat()
    )

    private fun encodeCurvePoints(pts: List<CurvePoint>): JSONArray = JSONArray().apply {
        pts.forEach { p ->
            put(JSONObject().apply {
                put("x", p.x.toDouble())
                put("y", p.y.toDouble())
            })
        }
    }

    private fun decodeCurvePoints(arr: JSONArray): List<CurvePoint> =
        (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            CurvePoint(
                x = p.getDouble("x").toFloat().coerceIn(0f, 1f),
                y = p.getDouble("y").toFloat().coerceIn(0f, 1f)
            )
        }

    private fun encodeAudioEffects(audio: AudioEffects): JSONObject = JSONObject().apply {
        put("volumeDb", audio.volumeDb.toDouble())
        put("pan", audio.pan.toDouble())
        put("voiceIsolationEnabled", audio.voiceIsolationEnabled)
        put("limiterEnabled", audio.limiterEnabled)
        put("limiterCeilingDb", audio.limiterCeilingDb.toDouble())
        put("volumeAutomation", encodeKeyframeTrack(audio.volumeAutomation))
        put("eqBands", JSONArray().apply {
            audio.eqBands.forEach { b ->
                put(JSONObject().apply {
                    put("type", b.type.name)
                    put("frequencyHz", b.frequencyHz.toDouble())
                    put("gainDb", b.gainDb.toDouble())
                    put("qFactor", b.qFactor.toDouble())
                    put("isEnabled", b.isEnabled)
                })
            }
        })
    }

    private fun decodeAudioEffects(obj: JSONObject): AudioEffects {
        val bandsArr = obj.getJSONArray("eqBands")
        val bands = (0 until bandsArr.length()).map { i ->
            val b = bandsArr.getJSONObject(i)
            EqBand(
                type = EqBandType.valueOf(b.getString("type")),
                frequencyHz = b.getDouble("frequencyHz").toFloat(),
                gainDb = b.getDouble("gainDb").toFloat(),
                qFactor = b.getDouble("qFactor").toFloat(),
                isEnabled = b.optBoolean("isEnabled", true)
            )
        }
        return AudioEffects(
            volumeDb = obj.optDouble("volumeDb", 0.0).toFloat(),
            pan = obj.optDouble("pan", 0.0).toFloat(),
            eqBands = bands,
            voiceIsolationEnabled = obj.optBoolean("voiceIsolationEnabled", false),
            limiterEnabled = obj.optBoolean("limiterEnabled", true),
            limiterCeilingDb = obj.optDouble("limiterCeilingDb", -0.3).toFloat(),
            volumeAutomation = decodeKeyframeTrack(obj.getJSONObject("volumeAutomation"))
        )
    }

    private fun encodeTransition(t: TransitionSpec): JSONObject = JSONObject().apply {
        put("type", t.type.name)
        put("durationUs", t.durationUs)
    }

    private fun decodeTransition(obj: JSONObject): TransitionSpec = TransitionSpec(
        type = TransitionType.valueOf(obj.getString("type")),
        durationUs = obj.getLong("durationUs")
    )
}
