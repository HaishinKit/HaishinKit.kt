package com.haishinkit.srt

/**
 * Process-wide libsrt logging configuration, shared by all SRT sockets.
 * Functional areas track only changes managed by this logger; native defaults remain
 * enabled until explicitly selected and subsequently removed, as in the Swift logger.
 */
object SrtLogger {
    enum class Level(internal val value: Int) {
        DEBUG(7),
        NOTICE(5),
        WARNING(4),
        ERROR(3),
        CRIT(2),
    }

    /** Functional areas defined by libsrt 1.5.7's SRT_LOGFA constants. */
    enum class FunctionalArea(internal val value: Int) {
        GENERAL(0),
        SOCK_MGMT(1),
        CONNECTION(2),
        TIMER(3),
        TSBPD(4),
        RSRC(5),
        HAICRYPT(6),
        CONGEST(7),
        PFILTER(8),
        APPLOG(10),
        API_CTRL(11),
        QUE_CTRL(13),
        EPOLL_UPD(16),
        API_RECV(21),
        BUF_RECV(22),
        QUE_RECV(23),
        CHN_RECV(24),
        GRP_RECV(25),
        API_SEND(31),
        BUF_SEND(32),
        QUE_SEND(33),
        CHN_SEND(34),
        GRP_SEND(35),
        INTERNAL(41),
        QUE_MGMT(43),
        CHN_MGMT(44),
        GRP_MGMT(45),
        EPOLL_API(46),
    }

    var level: Level = Level.NOTICE
        get() = synchronized(guard) { field }
        set(value) {
            synchronized(guard) {
                if (field != value) {
                    nativeSetLevel(value.value)
                    field = value
                }
            }
        }

    /**
     * Areas managed by this logger, not all areas enabled by libsrt.
     * Reads return a snapshot. Assignments copy the set and apply additions/removals.
     */
    var functionalAreas: Set<FunctionalArea> = emptySet()
        get() = synchronized(guard) { field.toSet() }
        set(value) {
            synchronized(guard) {
                val selected = value.toSet()
                for (area in field - selected) nativeRemoveFunctionalArea(area.value)
                for (area in selected - field) nativeAddFunctionalArea(area.value)
                field = selected
            }
        }

    private val guard = Any()

    init {
        System.loadLibrary("hksrt")
        nativeSetLevel(level.value)
    }

    private external fun nativeSetLevel(level: Int)

    private external fun nativeAddFunctionalArea(area: Int)

    private external fun nativeRemoveFunctionalArea(area: Int)
}
