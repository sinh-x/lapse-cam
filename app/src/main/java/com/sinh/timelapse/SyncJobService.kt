package com.sinh.timelapse

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.atomic.AtomicBoolean

/** Android may defer this job. A scheduler constraint is not proof of current Wi-Fi: recheck per request. */
class SyncJobService : JobService() {
    private var cancelled: AtomicBoolean? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val stop = AtomicBoolean(false); cancelled = stop
        SyncCoordinator.get(application as Application).sync(false, shouldStop = stop::get) { retry ->
            if (!stop.get()) jobFinished(params, retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { cancelled?.set(true); return true }
    companion object {
        private const val ID = 14014
        internal fun schedule(context: Context, enabled: Boolean) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!enabled) { scheduler.cancel(ID); return }
            val network = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            val job = JobInfo.Builder(ID, ComponentName(context, SyncJobService::class.java))
                .setRequiredNetwork(network).setPeriodic(15 * 60_000L).setPersisted(true)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
            check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "Android job unavailable" }
        }
    }
}
