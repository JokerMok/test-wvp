import { getChannelPlayStatus, playbackStates } from '@/api/channelPlayStatus'

export default {
  data() {
    return { playbackStatuses: {}, playbackTimer: null, playbackSequence: 0, playbackPendingKey: null, playbackDisposed: false }
  },
  watch: {
    playbackChannelIds() { this.refreshPlaybackStatus() }
  },
  mounted() { this.startPlaybackPolling() },
  activated() { this.startPlaybackPolling() },
  deactivated() { this.stopPlaybackPolling() },
  beforeDestroy() { this.stopPlaybackPolling() },
  methods: {
    startPlaybackPolling() {
      this.playbackDisposed = false
      if (this.playbackTimer) return
      this.refreshPlaybackStatus()
      this.playbackTimer = setInterval(() => {
        if (document.visibilityState !== 'hidden') this.refreshPlaybackStatus()
      }, 5000)
    },
    stopPlaybackPolling() {
      this.playbackDisposed = true
      this.playbackSequence++
      this.playbackPendingKey = null
      clearInterval(this.playbackTimer)
      this.playbackTimer = null
    },
    refreshPlaybackStatus() {
      if (this.playbackDisposed) return Promise.resolve()
      const ids = this.playbackChannelIds.filter(id => Number.isInteger(id) && id > 0)
      const key = ids.join(',')
      if (key === this.playbackPendingKey) return Promise.resolve()
      const sequence = ++this.playbackSequence
      if (!ids.length) { this.playbackStatuses = {}; this.playbackPendingKey = null; return Promise.resolve() }
      this.playbackPendingKey = key
      return getChannelPlayStatus(ids).then(statuses => {
        if (sequence !== this.playbackSequence || this.playbackDisposed) return
        const map = {}; statuses.forEach(status => { map[status.channelId] = status }); this.playbackStatuses = map
      }).catch(() => {
        if (sequence !== this.playbackSequence || this.playbackDisposed) return
        const map = {}; ids.forEach(id => { map[id] = { ...this.playbackStatuses[id], state: 'UNKNOWN' } }); this.playbackStatuses = map
      }).finally(() => {
        if (sequence === this.playbackSequence) this.playbackPendingKey = null
      })
    },
    playbackState(id) { return this.playbackStatuses[id] || {} },
    playbackLabel(id) { return playbackStates[this.playbackState(id).state] || '状态未知' },
    playbackType(id) {
      const state = this.playbackState(id).state
      return state === 'PLAYING' ? 'success' : (state === 'STALLED' ? 'warning' : 'info')
    },
    autoPlaybackLabel(id) {
      const state = this.playbackState(id)
      return state.autoPlayConfigured ? (state.autoPlayEnabled ? '已开启' : '已暂停') : '关闭'
    },
    canStopPlayback(id, storedStream) {
      const status = this.playbackState(id)
      return typeof status.canStop === 'boolean' ? status.canStop : !!storedStream
    }
  }
}
