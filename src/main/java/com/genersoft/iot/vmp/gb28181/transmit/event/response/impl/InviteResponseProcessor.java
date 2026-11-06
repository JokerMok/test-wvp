package com.genersoft.iot.vmp.gb28181.transmit.event.response.impl;

import com.genersoft.iot.vmp.gb28181.transmit.SIPProcessorObserver;
import com.genersoft.iot.vmp.gb28181.transmit.SIPSender;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.SIPRequestHeaderProvider;
import com.genersoft.iot.vmp.gb28181.transmit.event.response.SIPResponseProcessorAbstract;
import com.genersoft.iot.vmp.utils.IpPortUtil;
import gov.nist.javax.sip.ResponseEventExt;
import gov.nist.javax.sip.message.SIPResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sip.InvalidArgumentException;
import javax.sip.ResponseEvent;
import javax.sip.SipException;
import javax.sip.SipFactory;
import javax.sip.address.SipURI;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.text.ParseException;


/**
 * @description: 处理INVITE响应
 * @author: panlinlin
 * @date: 2021年11月5日 16：40
 */
@Slf4j
@Component
public class InviteResponseProcessor extends SIPResponseProcessorAbstract {

	private final String method = "INVITE";

	@Autowired
	private SIPProcessorObserver sipProcessorObserver;

	@Autowired
	private SIPSender sipSender;

	@Autowired
	private SIPRequestHeaderProvider headerProvider;

	@Override
	public void afterPropertiesSet() throws Exception {
		// 添加消息处理的订阅
		sipProcessorObserver.addResponseProcessor(method, this);
	}



	/**
	 * 处理invite响应
	 * 
	 * @param evt 响应消息
	 * @throws ParseException
	 */
	@Override
	public void process(ResponseEvent evt ){
		log.debug("接收到消息：" + evt.getResponse());
		try {
			SIPResponse response = (SIPResponse)evt.getResponse();
			int statusCode = response.getStatusCode();
			// trying不会回复
			if (statusCode == Response.TRYING) {
			}
			// 成功响应
			// 下发ack
			if (statusCode >= 200 && statusCode < 300) {
				ResponseEventExt event = (ResponseEventExt)evt;

				// ACK must still be sent for retransmitted 2xx without an SDP body.
				SipURI toUri = (SipURI) response.getToHeader().getAddress().getURI();
				SipURI requestUri = SipFactory.getInstance().createAddressFactory().createSipURI(toUri.getUser(), IpPortUtil.concatenateIpAndPort(event.getRemoteIpAddress(), String.valueOf(event.getRemotePort())));
				Request reqAck = headerProvider.createAckRequest(response.getLocalAddress().getHostAddress(), requestUri, response);

				sipSender.transmitRequest( response.getLocalAddress().getHostAddress(), reqAck);
				log.info("[回复ack] 提交发送, callId: {}, cseq: {}, target: {}, route: {}, responsePeer: {}:{}",
						response.getCallIdHeader().getCallId(), response.getCSeqHeader().getSeqNumber(),
						reqAck.getRequestURI(), reqAck.getHeader("Route"), event.getRemoteIpAddress(), event.getRemotePort());
			}
		} catch (InvalidArgumentException | ParseException | SipException e) {
			log.warn("[点播回复ACK] 发送失败, callId: {}", evt.getResponse().getHeader("Call-ID"), e);
		}
	}

}
