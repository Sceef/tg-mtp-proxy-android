package org.tgwsproxy.core

import java.util.concurrent.atomic.AtomicLong

class Stats {
    val connectionsTotal = AtomicLong()
    val connectionsActive = AtomicLong()
    val connectionsWs = AtomicLong()
    val connectionsCfproxy = AtomicLong()
    val connectionsTcpFallback = AtomicLong()
    val connectionsBad = AtomicLong()
    val wsErrors = AtomicLong()
    val bytesUp = AtomicLong()
    val bytesDown = AtomicLong()
    val poolHits = AtomicLong()
    val poolMisses = AtomicLong()
}
