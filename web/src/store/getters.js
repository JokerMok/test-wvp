const getters = {
  sidebar: state => state.app.sidebar,
  device: state => state.app.device,
  token: state => state.user.token,
  showConfirmBoxForLoginLose: state => state.user.showConfirmBoxForLoginLose,
  serverId: state => state.user.serverId,
  authority: state => state.user.authority,
  name: state => state.user.name,
  defaultPassword: state => state.user.defaultPassword,
  visitedViews: state => state.tagsView.visitedViews,
  cachedViews: state => state.tagsView.cachedViews
}
export default getters
