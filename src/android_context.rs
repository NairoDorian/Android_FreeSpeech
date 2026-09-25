use jni::objects::{GlobalRef, JClass, JObject};
use jni::sys::jboolean;
use jni::JNIEnv;
use once_cell::sync::OnceCell;

// The global reference is intentionally retained for the Android process lifetime:
// ndk-context stores the raw jobject supplied below.
static APPLICATION_CONTEXT: OnceCell<GlobalRef> = OnceCell::new();

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_NativeContextBootstrap_initializeNative(
    mut env: JNIEnv,
    _class: JClass,
    context: JObject,
) -> jboolean {
    let result = (|| -> jni::errors::Result<()> {
        let vm = env.get_java_vm()?;
        let application_context = env.new_global_ref(&context)?;

        APPLICATION_CONTEXT.get_or_try_init(|| {
            ndk_context::initialize_android_context(
                vm.get_java_vm_pointer() as *mut _,
                application_context.as_obj().as_raw() as *mut _,
            );
            Ok::<GlobalRef, jni::errors::Error>(application_context)
        })?;
        Ok(())
    })();

    if result.is_err() {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            "Unable to initialize the native audio runtime.",
        );
        return 0;
    }
    1
}
