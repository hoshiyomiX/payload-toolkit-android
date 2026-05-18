/*
 * pybridge.c — JNI bridge for embedding Python in Android
 *
 * Instead of execve()-ing the Python binary (which triggers Android linker
 * namespace issues on API 26+), we dlopen() libpython3.13.so directly from
 * within the app process and call Py_Main() via dlsym().
 *
 * Why this works:
 *   dlopen() uses the APP's linker namespace, which includes nativeLibraryDir.
 *   All dependencies (libandroid-support.so, libz.so, etc.) are found there.
 *   No LD_PRELOAD, no LD_LIBRARY_PATH hacks, no linker warnings.
 *
 * Build: zig cc -target aarch64-linux-android26 -shared -fPIC -I$JAVA_HOME/include
 *        -I$JAVA_HOME/include/linux -o libpybridge.so pybridge.c
 *
 * No NDK, no Python headers, no libpython linking at compile time.
 * Everything resolved at runtime via dlopen/dlsym.
 */

#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include <unistd.h>
#include <stdio.h>
#include <locale.h>

/* ═══════════════════════════════════════════════════════════════
 *  Pipe-based stdout/stderr capture
 *
 *  Python runs in-process (not a subprocess), so its stdout/stderr
 *  go to the app process's file descriptors.  We redirect fd 1/2
 *  to a pipe, and Java reads from the read end in a background thread.
 * ═══════════════════════════════════════════════════════════════ */

static int g_pipe_fds[2] = {-1, -1};
static int g_saved_stdout = -1;
static int g_saved_stderr = -1;

JNIEXPORT jint JNICALL
Java_com_hoshiyomi_payloadtoolkit_PyBridge_nativeSetupOutput(
    JNIEnv *env, jobject thiz)
{
    (void)env; (void)thiz;
    if (pipe(g_pipe_fds) != 0) return -1;
    g_saved_stdout = dup(STDOUT_FILENO);
    g_saved_stderr = dup(STDERR_FILENO);
    return g_pipe_fds[0];  /* return read end to Java */
}

JNIEXPORT void JNICALL
Java_com_hoshiyomi_payloadtoolkit_PyBridge_nativeRedirectOutput(
    JNIEnv *env, jobject thiz)
{
    (void)env; (void)thiz;
    if (g_pipe_fds[1] >= 0) {
        dup2(g_pipe_fds[1], STDOUT_FILENO);
        dup2(g_pipe_fds[1], STDERR_FILENO);
    }
}

JNIEXPORT void JNICALL
Java_com_hoshiyomi_payloadtoolkit_PyBridge_nativeFlushOutput(
    JNIEnv *env, jobject thiz)
{
    (void)env; (void)thiz;
    fflush(stdout);
    fflush(stderr);
}

JNIEXPORT void JNICALL
Java_com_hoshiyomi_payloadtoolkit_PyBridge_nativeRestoreOutput(
    JNIEnv *env, jobject thiz)
{
    (void)env; (void)thiz;
    if (g_saved_stdout >= 0) dup2(g_saved_stdout, STDOUT_FILENO);
    if (g_saved_stderr >= 0) dup2(g_saved_stderr, STDERR_FILENO);
    if (g_pipe_fds[1] >= 0) { close(g_pipe_fds[1]); g_pipe_fds[1] = -1; }
    g_saved_stdout = -1;
    g_saved_stderr = -1;
}

JNIEXPORT void JNICALL
Java_com_hoshiyomi_payloadtoolkit_PyBridge_nativeCloseReadFd(
    JNIEnv *env, jobject thiz)
{
    (void)env; (void)thiz;
    if (g_pipe_fds[0] >= 0) { close(g_pipe_fds[0]); g_pipe_fds[0] = -1; }
}

/* ═══════════════════════════════════════════════════════════════
 *  UTF-8 → wchar_t conversion
 *
 *  Android uses 4-byte wchar_t (UTF-32).  Python's Py_Main expects
 *  wchar_t **argv.  We convert Java's UTF-8 strings to wchar_t.
 *
 *  Uses mbrtowc() with explicit UTF-8 locale for correctness.
 * ═══════════════════════════════════════════════════════════════ */

static wchar_t *utf8_to_wcs(const char *utf8)
{
    if (!utf8) return NULL;
    size_t len = strlen(utf8);

    /* mbrtowc needs UTF-8 locale */
    setlocale(LC_CTYPE, "C.UTF-8");

    /* First pass: count wchar_t characters */
    size_t wcs_len = 0;
    mbstate_t st;
    memset(&st, 0, sizeof(st));
    const char *p = utf8;
    while (*p) {
        wchar_t wc;
        size_t n = mbrtowc(&wc, p, MB_CUR_MAX, &st);
        if (n == (size_t)-1 || n == (size_t)-2) break;  /* invalid UTF-8 */
        if (n == 0) break;
        p += n;
        wcs_len++;
    }

    wchar_t *wcs = (wchar_t *)calloc(wcs_len + 1, sizeof(wchar_t));
    if (!wcs) return NULL;

    /* Second pass: convert */
    memset(&st, 0, sizeof(st));
    p = utf8;
    size_t i = 0;
    while (*p && i < wcs_len) {
        size_t n = mbrtowc(&wcs[i], p, MB_CUR_MAX, &st);
        if (n == (size_t)-1 || n == (size_t)-2 || n == 0) break;
        p += n;
        i++;
    }
    wcs[wcs_len] = L'\0';
    return wcs;
}

/* ═══════════════════════════════════════════════════════════════
 *  Python execution via dlopen + Py_Main
 *
 *  Load libpython3.13.so from nativeLibraryDir, resolve Py_Main
 *  and Py_SetPythonHome, then run the .pyz file.
 *
 *  Py_Main is used (not Py_RunMain) because it takes argc/argv
 *  directly and returns exit code without calling exit().
 *  (In Python 3.13, Py_Main is deprecated but still functional.)
 * ═══════════════════════════════════════════════════════════════ */

JNIEXPORT jint JNICALL
Java_com_hoshiyomi_payloadtoolkit_PyBridge_nativeRunPython(
    JNIEnv *env, jobject thiz,
    jstring j_lib_dir,     /* nativeLibraryDir (contains all .so files) */
    jstring j_pyz_path,    /* absolute path to payload_toolkit.pyz */
    jstring j_stdlib_dir,  /* PYTHONHOME (extracted stdlib directory) */
    jobjectArray j_args    /* String[] arguments to pass */
) {
    (void)thiz;
    const char *lib_dir    = (*env)->GetStringUTFChars(env, j_lib_dir, NULL);
    const char *pyz_path   = (*env)->GetStringUTFChars(env, j_pyz_path, NULL);
    const char *stdlib_dir = (*env)->GetStringUTFChars(env, j_stdlib_dir, NULL);

    /* Force unbuffered Python output so pipe reader gets data immediately */
    setenv("PYTHONUNBUFFERED", "1", 1);
    setenv("PYTHONHOME", stdlib_dir, 1);
    setenv("PYTHONIOENCODING", "utf-8", 1);

    /* Build absolute path to libpython3.13.so */
    char python_so[1024];
    snprintf(python_so, sizeof(python_so), "%s/libpython3.13.so", lib_dir);

    /*
     * dlopen() with RTLD_NOW | RTLD_GLOBAL:
     *   RTLD_NOW   — resolve all symbols immediately (fail fast)
     *   RTLD_GLOBAL — export symbols so transitive deps can find them
     *
     * dlopen uses the APP's linker namespace, which includes nativeLibraryDir.
     * ALL deps of libpython3.13.so (libandroid-support.so, libz.so, etc.)
     * are resolved from nativeLibraryDir automatically.
     * No LD_PRELOAD, no LD_LIBRARY_PATH, no CANNOT LINK warnings.
     */
    void *handle = dlopen(python_so, RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        fprintf(stderr, "[pybridge] dlopen(%s) failed: %s\n", python_so, dlerror());
        (*env)->ReleaseStringUTFChars(env, j_lib_dir, lib_dir);
        (*env)->ReleaseStringUTFChars(env, j_pyz_path, pyz_path);
        (*env)->ReleaseStringUTFChars(env, j_stdlib_dir, stdlib_dir);
        return -1;
    }

    /* Resolve Python C API functions via dlsym */
    typedef void  (*Py_SetPythonHome_t)(const wchar_t *);
    typedef int   (*Py_Main_t)(int, wchar_t **);

    Py_SetPythonHome_t py_set_home =
        (Py_SetPythonHome_t)dlsym(handle, "Py_SetPythonHome");
    Py_Main_t py_main =
        (Py_Main_t)dlsym(handle, "Py_Main");

    if (!py_main) {
        /*
         * Python 3.13 may have removed Py_Main in a future version.
         * Fall back to Py_RunMain (void → int, uses global PyConfig).
         * For now, Py_Main is still present in CPython 3.13.
         */
        fprintf(stderr, "[pybridge] dlsym(Py_Main) failed: %s\n", dlerror());

        /* Try Py_RunMain as fallback */
        typedef int (*Py_RunMain_t)(void);
        Py_RunMain_t py_run_main =
            (Py_RunMain_t)dlsym(handle, "Py_RunMain");
        if (!py_run_main) {
            fprintf(stderr, "[pybridge] dlsym(Py_RunMain) also failed\n");
            dlclose(handle);
            (*env)->ReleaseStringUTFChars(env, j_lib_dir, lib_dir);
            (*env)->ReleaseStringUTFChars(env, j_pyz_path, pyz_path);
            (*env)->ReleaseStringUTFChars(env, j_stdlib_dir, stdlib_dir);
            return -2;
        }

        /* Py_RunMain uses PyConfig, which needs embedding API setup.
         * For simplicity, just report the error. */
        fprintf(stderr, "[pybridge] Py_RunMain found but embedding setup not yet implemented. "
                "Use Py_Main path.\n");
        dlclose(handle);
        (*env)->ReleaseStringUTFChars(env, j_lib_dir, lib_dir);
        (*env)->ReleaseStringUTFChars(env, j_pyz_path, pyz_path);
        (*env)->ReleaseStringUTFChars(env, j_stdlib_dir, stdlib_dir);
        return -3;
    }

    /* Set PYTHONHOME via C API (more reliable than env var for embedded mode) */
    if (py_set_home && stdlib_dir[0] != '\0') {
        wchar_t *home_w = utf8_to_wcs(stdlib_dir);
        if (home_w) {
            py_set_home(home_w);
            free(home_w);
        }
    }

    /* Build argc / argv for Py_Main */
    jsize java_argc = (*env)->GetArrayLength(env, j_args);
    int argc = (int)java_argc + 2;  /* +2: python3.13 + pyz_path */
    wchar_t **argv = (wchar_t **)calloc(argc, sizeof(wchar_t *));
    if (!argv) {
        dlclose(handle);
        (*env)->ReleaseStringUTFChars(env, j_lib_dir, lib_dir);
        (*env)->ReleaseStringUTFChars(env, j_pyz_path, pyz_path);
        (*env)->ReleaseStringUTFChars(env, j_stdlib_dir, stdlib_dir);
        return -4;
    }

    /* argv[0] = program name (for Python's sys.executable display) */
    argv[0] = utf8_to_wcs("python3.13");

    /* argv[1] = path to .pyz file */
    argv[1] = utf8_to_wcs(pyz_path);

    /* argv[2..] = user-supplied arguments */
    for (int i = 0; i < java_argc && (i + 2) < argc; i++) {
        jstring j_arg = (jstring)(*env)->GetObjectArrayElement(env, j_args, i);
        const char *arg = (*env)->GetStringUTFChars(env, j_arg, NULL);
        argv[i + 2] = utf8_to_wcs(arg);
        (*env)->ReleaseStringUTFChars(env, j_arg, arg);
    }

    /* Call Python! */
    int result = py_main(argc, argv);

    /* Cleanup argv */
    for (int i = 0; i < argc; i++) {
        if (argv[i]) free(argv[i]);
    }
    free(argv);

    /* Don't dlclose — Python registers atexit handlers that may run later.
     * The library stays loaded for the app's lifetime. */
    (void)handle;

    (*env)->ReleaseStringUTFChars(env, j_lib_dir, lib_dir);
    (*env)->ReleaseStringUTFChars(env, j_pyz_path, pyz_path);
    (*env)->ReleaseStringUTFChars(env, j_stdlib_dir, stdlib_dir);

    return result;
}
