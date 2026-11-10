<template>
  <el-dialog title="自动续播" :visible.sync="visible" width="820px" :close-on-click-modal="false" @open="load">
    <el-form inline size="small" @submit.native.prevent>
      <el-form-item label="已选通道">{{ selectedChannels.length }}</el-form-item>
      <el-form-item label="检查间隔"><el-input-number v-model="interval" :min="15" :max="3600" controls-position="right" /> 秒</el-form-item>
      <el-form-item><el-button type="primary" :disabled="!selectedChannels.length || saving" @click="batch(true)">开启所选</el-button><el-button :disabled="!selectedChannels.length || saving" @click="batch(false)">暂停所选</el-button></el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="tasks" size="small" max-height="420" v-loading="loading" empty-text="尚未配置自动续播">
      <el-table-column prop="channelName" label="通道" min-width="130" />
      <el-table-column prop="channelCode" label="编号" min-width="170" />
      <el-table-column label="状态" width="110"><template slot-scope="scope"><el-tooltip :content="scope.row.lastError || states[scope.row.status]" placement="top"><span>{{ states[scope.row.status] || scope.row.status }}</span></el-tooltip></template></el-table-column>
      <el-table-column label="检查间隔" width="90"><template slot-scope="scope">{{ scope.row.checkIntervalSeconds }} 秒</template></el-table-column>
      <el-table-column label="操作" width="80"><template slot-scope="scope"><el-button type="text" :disabled="saving" @click="toggle(scope.row)">{{ scope.row.enabled ? '暂停' : '开启' }}</el-button></template></el-table-column>
    </el-table>
    <span slot="footer"><el-button size="small" @click="load">刷新</el-button><el-button size="small" @click="visible = false">关闭</el-button></span>
  </el-dialog>
</template>
<script>
import { getAutoPlayTasks, setAutoPlayTasks, autoPlayStates } from '@/api/autoPlay'
export default {
  name: 'AutoPlayDialog', props: { selectedChannels: { type: Array, default: () => [] } },
  data() { return { visible: false, loading: false, saving: false, interval: 60, tasks: [], error: '', states: autoPlayStates } },
  methods: {
    open() { this.visible = true },
    load() { this.loading = true; this.error = ''; getAutoPlayTasks().then(data => { this.tasks = data }).catch(() => { this.error = '配置读取失败，请核对数据库升级和登录状态' }).finally(() => { this.loading = false }) },
    batch(enabled) { this.save(this.selectedChannels.map(channel => channel.gbId), enabled, this.interval) },
    toggle(task) { this.save([task.channelId], !task.enabled, task.checkIntervalSeconds) },
    save(ids, enabled, interval) {
      this.saving = true
      setAutoPlayTasks(ids, enabled, interval).then(() => { this.$message.success(enabled ? '自动续播已开启' : '自动续播已暂停'); this.$emit('updated'); this.load() }).catch(error => { this.error = typeof error === 'string' ? error : '保存失败，请检查通道和权限' }).finally(() => { this.saving = false })
    }
  }
}
</script>
