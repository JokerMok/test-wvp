package com.genersoft.iot.vmp.gb28181.dao;

import com.genersoft.iot.vmp.gb28181.bean.AutoPlayTask;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface AutoPlayMapper {
    String COLUMNS = "a.channel_id channelId,a.server_id serverId,a.enabled,a.check_interval_seconds checkIntervalSeconds," +
        "a.revision,a.status,a.failure_count failureCount,a.quiet_count quietCount,a.last_error lastError," +
        "a.last_check_time lastCheckTime,a.next_check_time nextCheckTime,a.last_success_time lastSuccessTime," +
        "a.lease_owner leaseOwner,a.lease_until leaseUntil,a.media_server_id mediaServerId,a.app,a.stream_id streamId";
    @Select("SELECT " + COLUMNS + ",COALESCE(c.gb_name,c.name) channelName,COALESCE(c.gb_device_id,c.device_id) channelCode FROM wvp_auto_play a LEFT JOIN wvp_device_channel c ON c.id=a.channel_id WHERE a.server_id=#{serverId} ORDER BY a.channel_id")
    List<AutoPlayTask> list(String serverId);

    @Select("SELECT " + COLUMNS + " FROM wvp_auto_play a WHERE a.server_id=#{serverId} AND a.channel_id=#{channelId}")
    AutoPlayTask get(@Param("serverId") String serverId,@Param("channelId") int channelId);

    @Insert("INSERT INTO wvp_auto_play (server_id,channel_id,enabled,check_interval_seconds,status,next_check_time) " +
        "VALUES (#{serverId},#{channelId},#{enabled},#{checkIntervalSeconds},#{status},0) ON DUPLICATE KEY UPDATE " +
        "enabled=VALUES(enabled),check_interval_seconds=VALUES(check_interval_seconds),status=VALUES(status),revision=revision+1," +
        "failure_count=0,quiet_count=0,last_error='',next_check_time=0,lease_owner=NULL,lease_until=0")
    int save(AutoPlayTask task);

    @Update("UPDATE wvp_auto_play SET enabled=0,status='PAUSED',revision=revision+1,lease_owner=NULL,lease_until=0,last_error=#{reason} WHERE server_id=#{serverId} AND channel_id=#{channelId} AND enabled=1")
    int pause(@Param("serverId") String serverId,@Param("channelId") int channelId,@Param("reason") String reason);

    @Select("SELECT " + COLUMNS + " FROM wvp_auto_play a WHERE server_id=#{serverId} AND enabled=1 AND next_check_time <= #{now} AND lease_until <= #{now} ORDER BY next_check_time,channel_id LIMIT 100")
    List<AutoPlayTask> due(@Param("serverId") String serverId,@Param("now") long now);

    @Update("UPDATE wvp_auto_play SET lease_owner=#{owner},lease_until=#{until},last_check_time=#{now} WHERE server_id=#{serverId} AND channel_id=#{channelId} AND revision=#{revision} AND enabled=1 AND lease_until <= #{now}")
    int acquire(@Param("serverId") String serverId,@Param("channelId") int channelId,@Param("revision") long revision,@Param("owner") String owner,@Param("now") long now,@Param("until") long until);

    @Update("UPDATE wvp_auto_play SET status=#{status},failure_count=#{failureCount},quiet_count=#{quietCount},last_error=#{lastError},next_check_time=#{nextCheckTime},last_success_time=#{lastSuccessTime},media_server_id=#{mediaServerId},app=#{app},stream_id=#{streamId},lease_owner=NULL,lease_until=0 WHERE server_id=#{serverId} AND channel_id=#{channelId} AND revision=#{revision} AND enabled=1 AND lease_owner=#{leaseOwner}")
    int finish(AutoPlayTask task);

    @Update("UPDATE wvp_auto_play SET status='RECONNECTING',media_server_id=#{mediaServerId},app=#{app},stream_id=#{streamId} WHERE server_id=#{serverId} AND channel_id=#{channelId} AND revision=#{revision} AND enabled=1 AND lease_owner=#{leaseOwner}")
    int preparing(AutoPlayTask task);

    @Select("SELECT COUNT(*) FROM wvp_auto_play WHERE server_id=#{serverId} AND enabled=1 AND app=#{app} AND stream_id=#{stream} AND (media_server_id IS NULL OR media_server_id=#{mediaId})")
    int keeping(@Param("serverId") String serverId,@Param("mediaId") String mediaId,@Param("app") String app,@Param("stream") String stream);
}
