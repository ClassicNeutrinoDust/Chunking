package com.chunking.engine.core;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/**
 * Best-effort Windows EcoQoS/P-core scheduling hints via Panama FFM SetThreadInformation.
 */
public final class ThreadHints {
    private static final int THREAD_POWER_THROTTLING = 3;
    private static final int THREAD_POWER_THROTTLING_CURRENT_VERSION = 1;
    private static final int THREAD_POWER_THROTTLING_EXECUTION_SPEED = 0x1;

    private static final MethodHandle GET_CURRENT_THREAD;
    private static final MethodHandle SET_THREAD_INFORMATION;
    private static final boolean WINDOWS_FFM_AVAILABLE;

    static {
        MethodHandle getCur = null;
        MethodHandle setInfo = null;
        boolean ok = false;
        try {
            String os = System.getProperty("os.name", "");
            if (os.toLowerCase(Locale.ROOT).startsWith("windows")) {
                SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
                Linker linker = Linker.nativeLinker();
                MemorySegment symGet = kernel32.find("GetCurrentThread").orElse(null);
                MemorySegment symSet = kernel32.find("SetThreadInformation").orElse(null);
                if (symGet != null && symSet != null) {
                    getCur = linker.downcallHandle(symGet, FunctionDescriptor.of(ValueLayout.ADDRESS));
                    setInfo = linker.downcallHandle(
                            symSet,
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT
                            )
                    );
                    ok = true;
                }
            }
        } catch (Throwable ignored) {
            ok = false;
        }
        GET_CURRENT_THREAD = getCur;
        SET_THREAD_INFORMATION = setInfo;
        WINDOWS_FFM_AVAILABLE = ok;
    }

    private ThreadHints() {}

    /** Disables OS power-throttling / EcoQoS on the calling thread; safe no-op if unsupported. */
    public static void preferHighPerformanceCurrentThread() {
        setPowerThrottlingState(0);
    }

    /**
     * Enables Windows 11 EcoQoS ({@code THREAD_POWER_THROTTLING_EXECUTION_SPEED = 1}) and lowers
     * Java thread priority to {@code NORM_PRIORITY - 1} so client background worldgen workers
     * steer onto E-cores on hybrid CPUs; safe no-op for OS hint if unsupported.
     */
    public static void enableEcoQosCurrentThread() {
        try {
            Thread.currentThread().setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        } catch (Throwable ignored) {
        }
        setPowerThrottlingState(THREAD_POWER_THROTTLING_EXECUTION_SPEED);
    }

    private static void setPowerThrottlingState(int stateMask) {
        if (!WINDOWS_FFM_AVAILABLE) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment hThread = (MemorySegment) GET_CURRENT_THREAD.invokeExact();
            // THREAD_POWER_THROTTLING_STATE { ULONG Version; ULONG ControlMask; ULONG StateMask; } (12 bytes)
            MemorySegment state = arena.allocate(12L, 4L);
            state.set(ValueLayout.JAVA_INT, 0L, THREAD_POWER_THROTTLING_CURRENT_VERSION);
            state.set(ValueLayout.JAVA_INT, 4L, THREAD_POWER_THROTTLING_EXECUTION_SPEED);
            state.set(ValueLayout.JAVA_INT, 8L, stateMask);
            int ignored = (int) SET_THREAD_INFORMATION.invokeExact(
                    hThread,
                    THREAD_POWER_THROTTLING,
                    state,
                    12
            );
        } catch (Throwable ignored) {
        }
    }
}
