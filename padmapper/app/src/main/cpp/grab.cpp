#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/input.h>
#include <stdint.h>

// open() runs inside the shell-uid Shizuku process, which is in group "input".
extern "C" JNIEXPORT jint JNICALL
Java_com_example_padmapper_Native_openRead(JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    int fd = open(p, O_RDONLY | O_CLOEXEC);
    env->ReleaseStringUTFChars(path, p);
    return fd;
}

// EVIOCGRAB: exclusive access. While grabbed, Android's InputReader (and thus the game) stops
// receiving this pad. The kernel auto-releases the grab when the fd closes, even if the process dies.
extern "C" JNIEXPORT jint JNICALL
Java_com_example_padmapper_Native_grab(JNIEnv*, jobject, jint fd, jboolean on) {
    return ioctl(fd, EVIOCGRAB, (intptr_t)(on ? 1 : 0));
}
