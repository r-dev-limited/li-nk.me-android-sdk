# Keep the SDK entrypoints when this library is built as a minified AAR. The
# release artifact is normally consumed by an application R8 run, so the same
# rule is also exported from consumer-rules.pro.
-keep public class me.link.sdk.** {
    public protected *;
}
