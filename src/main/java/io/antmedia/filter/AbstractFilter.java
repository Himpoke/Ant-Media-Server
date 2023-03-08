package io.antmedia.filter;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Queue;

import javax.servlet.Filter;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.UrlJwkProvider;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.antmedia.AntMediaApplicationAdapter;
import org.apache.catalina.util.NetMask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.ConfigurableWebApplicationContext;
import org.springframework.web.context.WebApplicationContext;

import io.antmedia.AppSettings;
import io.antmedia.datastore.db.DataStore;
import io.antmedia.datastore.db.DataStoreFactory;
import io.antmedia.datastore.db.IDataStoreFactory;
import io.antmedia.datastore.db.types.Broadcast;
import io.antmedia.settings.ServerSettings;
import io.antmedia.statistic.DashViewerStats;
import io.antmedia.statistic.HlsViewerStats;
import io.antmedia.statistic.IStreamStats;

public abstract class AbstractFilter implements Filter{

	protected static Logger logger = LoggerFactory.getLogger(AbstractFilter.class);
	protected FilterConfig config;
	
	IStreamStats streamStats;

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		this.config = filterConfig;
	}

	public AppSettings getAppSettings() 
	{
		AppSettings appSettings = null;
		ConfigurableWebApplicationContext context = getAppContext();
		if (context != null) {
			appSettings = (AppSettings)context.getBean(AppSettings.BEAN_NAME);
		}
		return appSettings;
	}

	public ServerSettings getServerSetting() 
	{
		ServerSettings serverSettings = null;
		ConfigurableWebApplicationContext context = getAppContext();
		if (context != null) {
			serverSettings = (ServerSettings)context.getBean(ServerSettings.BEAN_NAME);
		}
		return serverSettings;
	}

	public boolean checkCIDRList(Queue<NetMask> allowedCIDRList, final String remoteIPAdrress) {
		try {
			InetAddress addr = InetAddress.getByName(remoteIPAdrress);
			for (final NetMask nm : allowedCIDRList) {
				if (nm.matches(addr)) {
					return true;
				}
			}
		} catch (UnknownHostException e) {
			// This should be in the 'could never happen' category but handle it
			// to be safe.
			logger.error("error", e);
		}
		return false;
	}

	public ConfigurableWebApplicationContext getAppContext() 
	{
		ConfigurableWebApplicationContext appContext = getWebApplicationContext();
		if (appContext != null && appContext.isRunning()) 
		{
			Object dataStoreFactory = appContext.getBean(IDataStoreFactory.BEAN_NAME);
			
			if (dataStoreFactory instanceof IDataStoreFactory) 
			{
				DataStore dataStore = ((IDataStoreFactory)dataStoreFactory).getDataStore();
				if (dataStore.isAvailable()) 
				{
					return appContext;
				}
				else {
					logger.warn("DataStore is not available. It may be closed or not initialized");
				}
			}
			else {
				//return app context if it's not app's IDataStoreFactory
				return appContext;
			}
		}
		else 
		{
			if (appContext == null) {
				logger.warn("App context not initialized ");
			}
			else {
				logger.warn("App context not running yet." );
			}
		}

		return null;
	}
	
	public ConfigurableWebApplicationContext getWebApplicationContext() 
	{
		return (ConfigurableWebApplicationContext) getConfig().getServletContext().getAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE);
	}

	public FilterConfig getConfig() {
		return config;
	}

	public void setConfig(FilterConfig config) {
		this.config = config;
	}

	@Override
	public void destroy() {
		//nothing to destroy
	}
	
	public IStreamStats getStreamStats(String type) {
		if (streamStats == null) {
			ApplicationContext context = getAppContext();
			if (context != null) 
			{
				if(type.equals(HlsViewerStats.BEAN_NAME)) {
					streamStats = (IStreamStats)context.getBean(HlsViewerStats.BEAN_NAME);
				}
				else {
					streamStats = (IStreamStats)context.getBean(DashViewerStats.BEAN_NAME);
				}
			}
		}
		return streamStats;
	}
	
	public Broadcast getBroadcast(String streamId) {
		Broadcast broadcast = null;	
		ApplicationContext context = getAppContext();
		if (context != null) 
		{
			DataStoreFactory dsf = (DataStoreFactory)context.getBean(IDataStoreFactory.BEAN_NAME);
			broadcast = dsf.getDataStore().get(streamId);
		}
		return broadcast;
	}

	protected AntMediaApplicationAdapter getAntMediaApplicationAdapter(){
		AntMediaApplicationAdapter antMediaApplicationAdapter = null;
		ApplicationContext context = getAppContext();
		if (context != null)
		{
			antMediaApplicationAdapter= (AntMediaApplicationAdapter)context.getBean(AntMediaApplicationAdapter.BEAN_NAME);
		}
		return antMediaApplicationAdapter;

	}
	protected boolean checkJWT(String jwtString) {
		boolean result = true;
		try {
			AppSettings appSettings = getAppSettings();
			String jwksURL = appSettings.getJwksURL();

			if (jwksURL != null && !jwksURL.isEmpty()) {
				DecodedJWT jwt = JWT.decode(jwtString);
				JwkProvider provider = new UrlJwkProvider(appSettings.getJwksURL());
				Jwk jwk = provider.get(jwt.getKeyId());
				Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) jwk.getPublicKey(), null);
				algorithm.verify(jwt);
			}
			else {
				Algorithm algorithm = Algorithm.HMAC256(appSettings.getJwtSecretKey());
				JWTVerifier verifier = JWT.require(algorithm)
						.build();
				verifier.verify(jwtString);
			}

		}
		catch (JWTVerificationException ex) {
			logger.error(ex.toString());
			result = false;
		} catch (JwkException e) {
			logger.error(e.toString());
			result = false;
		}
		return result;
	}
	
}
