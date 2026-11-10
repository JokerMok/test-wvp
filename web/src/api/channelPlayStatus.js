import request from '@/utils/request'

export function getChannelPlayStatus(channelIds) {
  return request({ url: '/api/common/channel/play-status', method: 'get', params: { channelIds: channelIds.join(',') } })
    .then(response => response.data)
}

export const playbackStates = { PLAYING: '收流中', STARTING: '点播中', STOPPED: '未收流', STALLED: '媒体停滞', UNKNOWN: '状态未知', REMOVED: '通道已删除' }
