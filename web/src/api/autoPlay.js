import request from '@/utils/request'
export const autoPlayStates = { PAUSED: '已暂停', WAITING: '等待检查', RECONNECTING: '续播中', PLAYING: '收流中', RETRYING: '等待重试', OFFLINE: '等待设备上线', SIGNAL_OFFLINE: '待设备注册', CHANNEL_OFFLINE: '通道离线', UNKNOWN: '状态未知', QUIET: '等待复核' }
export function getAutoPlayTasks() { return request({ url: '/api/common/channel/auto-play', method: 'get' }).then(response => response.data) }
export function setAutoPlayTasks(channelIds, enabled, checkIntervalSeconds = 60) { return request({ url: '/api/common/channel/auto-play', method: 'post', data: { channelIds, enabled, checkIntervalSeconds } }).then(response => response.data) }
