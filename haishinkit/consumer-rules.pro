# ShaderLoader and VideoEffect discover shader requirements/uniforms at runtime.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep @interface com.haishinkit.graphics.glsl.RequirementsDirective { *; }
-keep @interface com.haishinkit.graphics.glsl.Uniform { *; }

# Full mode otherwise discards annotations on unkept effect classes, selecting
# the ES 2 vertex shader for an ES 3 fragment shader (a black preview).
-keep,allowoptimization,allowobfuscation @com.haishinkit.graphics.glsl.RequirementsDirective class *

# Kotlin property annotations are on synthetic getX$annotations methods.
# Their names and the matching getters determine the GLSL uniform name.
-keepclassmembers class * implements com.haishinkit.graphics.effect.VideoEffect {
    @com.haishinkit.graphics.glsl.Uniform <methods>;
    public *** get*();
}
