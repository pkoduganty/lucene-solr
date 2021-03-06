/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.servlet;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.solr.client.solrj.impl.HttpClientConfigurer;
import org.apache.solr.client.solrj.impl.HttpClientUtil;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.NodeConfig;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.core.SolrXmlConfig;
import org.apache.solr.security.AuthenticationPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This filter looks at the incoming URL maps them to handlers defined in solrconfig.xml
 *
 * @since solr 1.2
 */
public class SolrDispatchFilter extends BaseSolrFilter {
  static final Logger log = LoggerFactory.getLogger(SolrDispatchFilter.class);

  protected volatile CoreContainer cores;

  protected String abortErrorMessage = null;
  protected final CloseableHttpClient httpClient = HttpClientUtil.createClient(new ModifiableSolrParams());
  private ArrayList<Pattern> excludePatterns;

  /**
   * Enum to define action that needs to be processed.
   * PASSTHROUGH: Pass through to Restlet via webapp.
   * FORWARD: Forward rewritten URI (without path prefix and core/collection name) to Restlet
   * RETURN: Returns the control, and no further specific processing is needed.
   *  This is generally when an error is set and returned.
   * RETRY:Retry the request. In cases when a core isn't found to work with, this is set.
   */
  enum Action {
    PASSTHROUGH, FORWARD, RETURN, RETRY, ADMIN, REMOTEQUERY, PROCESS
  }
  
  public SolrDispatchFilter() {
  }

  public static final String PROPERTIES_ATTRIBUTE = "solr.properties";

  public static final String SOLRHOME_ATTRIBUTE = "solr.solr.home";
  
  @Override
  public void init(FilterConfig config) throws ServletException
  {
    log.info("SolrDispatchFilter.init()" + this.getClass().getClassLoader());
    String exclude = config.getInitParameter("excludePatterns");
    if(exclude != null) {
      String[] excludeArray = exclude.split(",");
      excludePatterns = new ArrayList();
      for (String element : excludeArray) {
        excludePatterns.add(Pattern.compile(element));
      }
    }
    try {
      Properties extraProperties = (Properties) config.getServletContext().getAttribute(PROPERTIES_ATTRIBUTE);
      if (extraProperties == null)
        extraProperties = new Properties();

      String solrHome = (String) config.getServletContext().getAttribute(SOLRHOME_ATTRIBUTE);
      if (solrHome == null)
        solrHome = SolrResourceLoader.locateSolrHome();

      this.cores = createCoreContainer(solrHome, extraProperties);

      if (this.cores.getAuthenticationPlugin() != null) {
        HttpClientConfigurer configurer = this.cores.getAuthenticationPlugin().getDefaultConfigurer();
        if (configurer != null) {
          configurer.configure((DefaultHttpClient)httpClient, new ModifiableSolrParams());
        }
      }

      log.info("user.dir=" + System.getProperty("user.dir"));
    }
    catch( Throwable t ) {
      // catch this so our filter still works
      log.error( "Could not start Solr. Check solr/home property and the logs");
      SolrCore.log( t );
      if (t instanceof Error) {
        throw (Error) t;
      }
    }

    log.info("SolrDispatchFilter.init() done");
  }

  /**
   * Override this to change CoreContainer initialization
   * @return a CoreContainer to hold this server's cores
   */
  protected CoreContainer createCoreContainer(String solrHome, Properties extraProperties) {
    NodeConfig nodeConfig = loadNodeConfig(solrHome, extraProperties);
    cores = new CoreContainer(nodeConfig, extraProperties);
    cores.load();
    return cores;
  }

  /**
   * Get the NodeConfig whether stored on disk, in ZooKeeper, etc.
   * This may also be used by custom filters to load relevant configuration.
   * @return the NodeConfig
   */
  public static NodeConfig loadNodeConfig(String solrHome, Properties nodeProperties) {

    SolrResourceLoader loader = new SolrResourceLoader(solrHome, null, nodeProperties);

    String solrxmlLocation = System.getProperty("solr.solrxml.location", "solrhome");

    if (solrxmlLocation == null || "solrhome".equalsIgnoreCase(solrxmlLocation))
      return SolrXmlConfig.fromSolrHome(loader, loader.getInstanceDir());

    if ("zookeeper".equalsIgnoreCase(solrxmlLocation)) {
      String zkHost = System.getProperty("zkHost");
      log.info("Trying to read solr.xml from " + zkHost);
      if (StringUtils.isEmpty(zkHost))
        throw new SolrException(ErrorCode.SERVER_ERROR,
            "Could not load solr.xml from zookeeper: zkHost system property not set");
      try (SolrZkClient zkClient = new SolrZkClient(zkHost, 30000)) {
        if (!zkClient.exists("/solr.xml", true))
          throw new SolrException(ErrorCode.SERVER_ERROR, "Could not load solr.xml from zookeeper: node not found");
        byte[] data = zkClient.getData("/solr.xml", null, null, true);
        return SolrXmlConfig.fromInputStream(loader, new ByteArrayInputStream(data));
      } catch (Exception e) {
        throw new SolrException(ErrorCode.SERVER_ERROR, "Could not load solr.xml from zookeeper", e);
      }
    }

    throw new SolrException(ErrorCode.SERVER_ERROR,
        "Bad solr.solrxml.location set: " + solrxmlLocation + " - should be 'solrhome' or 'zookeeper'");
  }
  
  public CoreContainer getCores() {
    return cores;
  }
  
  @Override
  public void destroy() {
    try {
      if (cores != null) {
        cores.shutdown();
        cores = null;
      }
    } finally {
      IOUtils.closeQuietly(httpClient);
    }
  }
  
  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
    doFilter(request, response, chain, false);
  }
  
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain, boolean retry) throws IOException, ServletException {
    if (!(request instanceof HttpServletRequest)) return;

    AtomicReference<ServletRequest> wrappedRequest = new AtomicReference();
    if (!authenticateRequest(request, response, wrappedRequest)) { // the response and status code have already been sent
      return;
    }
    if (wrappedRequest.get() != null) {
      request = wrappedRequest.get();
    }
    if (cores.getAuthenticationPlugin() != null) {
      log.debug("User principal: "+((HttpServletRequest)request).getUserPrincipal());
    }

    // No need to even create the HttpSolrCall object if this path is excluded.
    if(excludePatterns != null) {
      String servletPath = ((HttpServletRequest) request).getServletPath().toString();
      for (Pattern p : excludePatterns) {
        Matcher matcher = p.matcher(servletPath);
        if (matcher.lookingAt()) {
          chain.doFilter(request, response);
          return;
        }
      }
    }
    
    HttpSolrCall call = new HttpSolrCall(this, cores, (HttpServletRequest) request, (HttpServletResponse) response, retry);
    try {
      Action result = call.call();
      switch (result) {
        case PASSTHROUGH:
          chain.doFilter(request, response);
          break;
        case RETRY:
          doFilter(request, response, chain, true);
          break;
        case FORWARD:
          request.getRequestDispatcher(call.getPath()).forward(request, response);
          break;
      }  
    } finally {
      call.destroy();
    }
  }

  private boolean authenticateRequest(ServletRequest request, ServletResponse response, final AtomicReference<ServletRequest> wrappedRequest) throws IOException {
    final AtomicBoolean isAuthenticated = new AtomicBoolean(false);
    AuthenticationPlugin authenticationPlugin = cores.getAuthenticationPlugin();
    if (authenticationPlugin == null) {
      return true;
    } else {
      try {
        log.debug("Request to authenticate: "+request+", domain: "+request.getLocalName()+", port: "+request.getLocalPort());
        // upon successful authentication, this should call the chain's next filter.
        authenticationPlugin.doAuthenticate(request, response, new FilterChain() {
          public void doFilter(ServletRequest req, ServletResponse rsp) throws IOException, ServletException {
            isAuthenticated.set(true);
            wrappedRequest.set(req);
          }
        });
      } catch (Exception e) {
        e.printStackTrace();
        throw new SolrException(ErrorCode.SERVER_ERROR, "Error during request authentication, ", e);
      }
    }
    // failed authentication?
    if (!isAuthenticated.get()) {
      response.flushBuffer();
      return false;
    }
    return true;
  }
}
