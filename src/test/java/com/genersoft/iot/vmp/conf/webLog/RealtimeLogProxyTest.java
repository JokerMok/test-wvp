package com.genersoft.iot.vmp.conf.webLog;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.LoggingEvent;
import jakarta.servlet.*;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.*;
import org.apache.tomcat.websocket.server.WsSci;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RealtimeLogProxyTest {
    @TempDir Path temp;
    String command(String... args) throws Exception {
        Process process=new ProcessBuilder(args).redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes());
        if(!process.waitFor(20,TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("Fixture command timed out");}
        if(process.exitValue()!=0)throw new AssertionError(output);
        return output.strip();
    }
    @Test void realLogFrameTravelsThroughTheFormalNginxTemplateAndAnonymousUpgradeIsDenied() throws Exception {
        String container="wvp-log-verify-"+UUID.randomUUID().toString().substring(0,8);
        String token="fixture-"+UUID.randomUUID();Tomcat tomcat=new Tomcat();WebSocket socket=null;
        tomcat.setBaseDir(temp.resolve("tomcat").toString());tomcat.setPort(0);tomcat.getConnector().setProperty("address","0.0.0.0");
        Path web=Files.createDirectory(temp.resolve("web"));var context=tomcat.addContext("",web.toString());context.setParentClassLoader(LogChannel.class.getClassLoader());
        Tomcat.addServlet(context,"default",new org.apache.catalina.servlets.DefaultServlet());context.addServletMappingDecoded("/","default");
        context.addServletContainerInitializer(new WsSci(),Set.of(LogChannel.class));
        FilterDef definition=new FilterDef();definition.setFilterName("fixture-auth");definition.setFilter(new Filter(){
            public void doFilter(ServletRequest req,ServletResponse res,FilterChain chain)throws java.io.IOException,ServletException{
                var request=(jakarta.servlet.http.HttpServletRequest)req;var response=(jakarta.servlet.http.HttpServletResponse)res;
                if(!token.equals(request.getHeader("Sec-WebSocket-Protocol"))){response.setStatus(401);return;}
                response.setHeader("Sec-WebSocket-Protocol",token);chain.doFilter(req,res);
            }
        });context.addFilterDef(definition);FilterMap mapping=new FilterMap();mapping.setFilterName("fixture-auth");mapping.addURLPattern("/channel/log");context.addFilterMap(mapping);
        try {
            tomcat.start();int backend=tomcat.getConnector().getLocalPort();
            String template=Files.readString(Path.of("docker/nginx/templates/nginx.conf.template"));
            String upstream="host.docker.internal:"+backend;
            Path config=temp.resolve("nginx.conf");Files.writeString(config,template.replace("polaris-wvp:18978",upstream).replace("polaris-media:80",upstream).replace("${Stream_IP}","127.0.0.1"));
            command("docker","run","--rm","-d","--name",container,"--memory","64m","--cpus","0.25","-p","127.0.0.1::8080","-v",config+":/etc/nginx/conf.d/default.conf:ro","nginx:alpine");
            int port=Integer.parseInt(command("docker","port",container,"8080").substring("127.0.0.1:".length()));
            URI http=URI.create("http://127.0.0.1:"+port+"/channel/log"),ws=URI.create("ws://127.0.0.1:"+port+"/channel/log");
            HttpClient client=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
            boolean ready=false;for(int i=0;i<40;i++){
                try {if(client.send(HttpRequest.newBuilder(http).timeout(java.time.Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==401){ready=true;break;}}catch(Exception ignored){}
                Thread.sleep(100);
            }
            assertTrue(ready,"Nginx must reach the fixture backend");
            var denied=assertThrows(ExecutionException.class,()->client.newWebSocketBuilder().buildAsync(ws,new WebSocket.Listener(){}).get(5,TimeUnit.SECONDS));
            assertInstanceOf(WebSocketHandshakeException.class,denied.getCause());assertEquals(401,((WebSocketHandshakeException)denied.getCause()).getResponse().statusCode());
            CountDownLatch frame=new CountDownLatch(1);StringBuilder received=new StringBuilder();
            socket=client.newWebSocketBuilder().subprotocols(token).buildAsync(ws,new WebSocket.Listener(){
                public void onOpen(WebSocket s){s.request(1);}
                public CompletionStage<?> onText(WebSocket s,CharSequence text,boolean last){received.append(text);if(received.toString().contains("fresh-log-marker"))frame.countDown();s.request(1);return null;}
            }).get(5,TimeUnit.SECONDS);
            for(int i=0;i<20 && LogChannel.CHANNELS.isEmpty();i++)Thread.sleep(50);
            assertFalse(LogChannel.CHANNELS.isEmpty());
            var loggerContext=(LoggerContext)LoggerFactory.getILoggerFactory();var encoder=new PatternLayoutEncoder();encoder.setContext(loggerContext);encoder.setPattern("%msg%n");encoder.start();
            var appender=new WebSocketAppender();appender.setContext(loggerContext);appender.setEncoder(encoder);appender.start();
            appender.doAppend(new LoggingEvent(getClass().getName(),loggerContext.getLogger("fixture"),Level.INFO,"fresh-log-marker",null,null));
            assertTrue(frame.await(5,TimeUnit.SECONDS),"A newly generated log frame must reach the WebSocket client");appender.stop();encoder.stop();
        } finally {
            if(socket!=null)socket.abort();
            new ProcessBuilder("docker","rm","-f",container).redirectErrorStream(true).start().waitFor(10,TimeUnit.SECONDS);
            try {tomcat.stop();}finally{tomcat.destroy();LogChannel.CHANNELS.clear();}
        }
    }
}
