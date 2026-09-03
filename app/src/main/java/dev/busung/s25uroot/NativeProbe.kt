package dev.busung.s25uroot

object NativeProbe {
    init { System.loadLibrary("native_probe") }

    external fun run(): String
    external fun isKernelSuActive(): Boolean

    /**
     * Returns the exit-signal number delivered to [pid] as reported by
     * `/proc/<pid>/stat` field 35 (exit_signal).  Returns 0 if the process
     * exited normally without a signal, or -1 if the stat file cannot be
     * read or parsed (e.g. the process has already been reaped).
     */
    external fun getProcessSignal(pid: Int): Int
}
