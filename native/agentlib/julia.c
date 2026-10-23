// Windows loader for the Java agent: -agentlib:julia=<options> behaves like -javaagent:julia-agent.jar=<options>.
//
// PZ's Windows launcher lives in the game folder, not jre64\bin, so the JVM cannot resolve instrument.dll's
// java.dll and jli.dll imports and every -javaagent fails before the game starts. This library loads instrument.dll
// with jre64\bin on the DLL search path and hands it the agent jar, as java.exe would have.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#define AGENT_JAR "julia-agent.jar"
#define JNI_ERR (-1)

typedef int (*AgentEntry)(void *vm, char *options, void *reserved);

static void report(const char *message) {
    HANDLE err = GetStdHandle(STD_ERROR_HANDLE);
    DWORD written;
    if (err != NULL && err != INVALID_HANDLE_VALUE) WriteFile(err, message, lstrlenA(message), &written, NULL);
}

__declspec(dllexport) int Agent_OnLoad(void *vm, char *options, void *reserved) {
    // The launcher runs the JVM with the game folder as its working directory.
    SetDllDirectoryA(".\\jre64\\bin");
    HMODULE instrument = LoadLibraryA("instrument.dll");
    SetDllDirectoryA(NULL);
    if (instrument == NULL) {
        report("[julia] Cannot load jre64\\bin\\instrument.dll\n");
        return JNI_ERR;
    }
    AgentEntry load = (AgentEntry)GetProcAddress(instrument, "Agent_OnLoad");
    if (load == NULL) {
        report("[julia] instrument.dll has no Agent_OnLoad\n");
        return JNI_ERR;
    }

    char arguments[2048];
    lstrcpynA(arguments, AGENT_JAR, sizeof arguments);
    if (options != NULL && options[0] != '\0') {
        int used = lstrlenA(arguments);
        if (used + 1 + lstrlenA(options) >= (int)sizeof arguments) {
            report("[julia] Agent options are too long\n");
            return JNI_ERR;
        }
        arguments[used] = '=';
        lstrcpynA(arguments + used + 1, options, sizeof arguments - used - 1);
    }
    return load(vm, arguments, reserved);
}
