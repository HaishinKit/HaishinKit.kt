package com.haishinkit.gles

/** Shader conversions for a linear BT.2020 working space, normalized to 1000 cd/m². */
internal object HlgShaderSource {
    private const val FUNCTIONS = """
mat3 hk709to2020 = mat3(0.627404,0.069097,0.016391,0.329283,0.919540,0.088013,0.043313,0.011362,0.895595);
vec3 hkHlgInverse(vec3 v) {
    return mix(v*v/3.0,(exp((v-0.55991073)/0.17883277)+0.28466892)/12.0,step(vec3(0.5),v));
}
vec3 hkHlg(vec3 v) {
    return mix(sqrt(3.0*v),0.17883277*log(max(12.0*v-0.28466892,vec3(0.000001)))+0.55991073,step(vec3(1.0/12.0),v));
}
"""

    // 1: sRGB -> working, 2: limited-range HLG YUV -> working, 3: working -> HLG,
    // 4: BT.709 SDR video -> working. Zero preserves the existing SDR pipeline.
    fun convert(
        source: String,
        vertex: Boolean,
        external: Boolean,
        conversion: Int,
    ): String {
        if (conversion == 0) return source
        var body =
            source.replace(Regex("(?m)^#version[^\\n]*\\n?"), "")
                .replace(Regex("(?m)^#extension[^\\n]*\\n?"), "")
        if (vertex) {
            return "#version 300 es\n" + body.replace("attribute ", "in ").replace("varying ", "out ")
        }
        val sampler =
            if (external && conversion == 2) {
                "__samplerExternal2DY2YEXT"
            } else if (external) {
                "samplerExternalOES"
            } else {
                "sampler2D"
            }
        val extension =
            if (external && conversion == 2) {
                "#extension GL_EXT_YUV_target : require\n"
            } else if (external) {
                "#extension GL_OES_EGL_image_external_essl3 : require\n"
            } else {
                ""
            }
        body =
            body.replace("precision mediump float", "precision highp float").replace("varying ", "in ")
                .replace("samplerExternalOES", sampler).replace("uniform $sampler ", "uniform highp $sampler ")
        val output = if (body.contains("gl_FragColor")) "hkColor" else "fragColor"
        if (output == "hkColor") body = body.replace("gl_FragColor", output)
        val declaration = if (output == "hkColor") "out highp vec4 hkColor;\n" else ""
        if (conversion != 3) {
            body = body.replace("texture2D(", "hkSample(").replace("texture(", "hkSample(")
        }
        if (conversion == 3) body = body.replace("texture2D(", "texture(")
        body = body.replace("void main()", "void hkMain()")
        val sampled =
            when (conversion) {
                2 -> "vec3 yuv = (c.rgb - vec3(64.0,512.0,512.0)/1023.0) * vec3(1023.0/876.0,1023.0/896.0,1023.0/896.0); vec3 rgb = vec3(yuv.x+1.4746*yuv.z,yuv.x-0.164553*yuv.y-0.571353*yuv.z,yuv.x+1.8814*yuv.y); vec3 scene = hkHlgInverse(clamp(rgb,0.0,1.0)); float y = dot(scene,vec3(0.2627,0.6780,0.0593)); c.rgb = scene * pow(max(y,0.0),0.2);"
                4 -> "vec3 linear = mix(c.rgb/4.5,pow((c.rgb+0.099)/1.099,vec3(1.0/0.45)),step(vec3(0.081),c.rgb)); c.rgb = hk709to2020 * linear * 0.203;"
                else -> "vec3 linear = mix(c.rgb/12.92,pow((c.rgb+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),c.rgb)); c.rgb = hk709to2020 * linear * 0.203;"
            }
        val sample = if (conversion == 3) "" else "vec4 hkSample(highp $sampler tex, vec2 uv) { vec4 c = texture(tex, uv); $sampled return c; }\n"
        val finish = if (conversion == 3) "vec3 d=max($output.rgb,vec3(0.0)); float y=dot(d,vec3(0.2627,0.6780,0.0593)); vec3 scene=d*pow(max(y,0.000001),-0.2/1.2); $output.rgb=hkHlg(clamp(scene,0.0,1.0));" else ""
        return "#version 300 es\n" + extension + "precision highp float;\n" + declaration + FUNCTIONS + sample + body + "\nvoid main() { hkMain(); $finish }\n"
    }
}
