# The SDK is consumed through the LinkMe singleton and its nested Config and
# LinkPayload types. Preserve the public API when an application enables R8;
# the library's own minified verification build has no executable entrypoint
# that R8 could otherwise use to infer these classes are reachable.
-keep public class me.link.sdk.** {
    public protected *;
}
