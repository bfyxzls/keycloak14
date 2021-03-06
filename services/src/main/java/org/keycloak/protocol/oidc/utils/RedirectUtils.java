/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.protocol.oidc.utils;

import org.jboss.logging.Logger;
import org.keycloak.common.util.UriUtils;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakUriInfo;
import org.keycloak.models.RealmModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.services.Urls;
import org.keycloak.services.util.ResolveRelative;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class RedirectUtils {

  private static final Logger logger = Logger.getLogger(RedirectUtils.class);

  public static String verifyRealmRedirectUri(KeycloakSession session, String redirectUri) {
    Set<String> validRedirects = getValidateRedirectUris(session);
    return verifyRedirectUri(session, null, redirectUri, validRedirects, true);
  }

  public static String verifyRedirectUri(KeycloakSession session, String redirectUri, ClientModel client) {
    return verifyRedirectUri(session, redirectUri, client, true);
  }

  public static String verifyRedirectUri(KeycloakSession session, String redirectUri, ClientModel client, boolean requireRedirectUri) {
    if (client != null)
      return verifyRedirectUri(session, client.getRootUrl(), redirectUri, client.getRedirectUris(), requireRedirectUri);
    return null;
  }

  public static Set<String> resolveValidRedirects(KeycloakSession session, String rootUrl, Set<String> validRedirects) {
    // If the valid redirect URI is relative (no scheme, host, port) then use the request's scheme, host, and port
    Set<String> resolveValidRedirects = new HashSet<>();
    for (String validRedirect : validRedirects) {
      if (validRedirect.startsWith("/")) {
        validRedirect = relativeToAbsoluteURI(session, rootUrl, validRedirect);
        logger.debugv("replacing relative valid redirect with: {0}", validRedirect);
        resolveValidRedirects.add(validRedirect);
      } else {
        resolveValidRedirects.add(validRedirect);
      }
    }
    return resolveValidRedirects;
  }

  private static Set<String> getValidateRedirectUris(KeycloakSession session) {
    RealmModel realm = session.getContext().getRealm();
    return session.clientStorageManager().getAllRedirectUrisOfEnabledClients(realm).entrySet().stream()
        .filter(me -> me.getKey().isEnabled() && OIDCLoginProtocol.LOGIN_PROTOCOL.equals(me.getKey().getProtocol()) && !me.getKey().isBearerOnly() && (me.getKey().isStandardFlowEnabled() || me.getKey().isImplicitFlowEnabled()))
        .map(me -> resolveValidRedirects(session, me.getKey().getRootUrl(), me.getValue()))
        .flatMap(Collection::stream)
        .collect(Collectors.toSet());
  }

  public static String getUrlEncodeParams(String needValid) {
    if (needValid.indexOf("?") > 1) {
      String url = needValid.substring(0, needValid.indexOf("?"));
      String param = needValid.substring(needValid.indexOf("?"));
      String[] paramList = param.split("&");
      List<String> paramEncode = new ArrayList<>();
      for (String item : paramList) {
        String[] valAttr = item.split("=");
        if (valAttr.length > 1) {
          try {
            paramEncode.add(valAttr[0] + "=" + URLEncoder.encode(valAttr[1], "UTF-8"));
          } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
          }
        }
      }
      needValid = needValid.substring(0, needValid.indexOf("?")) + String.join("&", paramEncode);
      logger.info("needValid.url:" + needValid);
      return needValid;
    }
    return needValid;
  }

  public static String removeUrlSpaceParams(String needValid) {
    needValid = needValid.replaceAll(" ", "%20");
    needValid = needValid.replaceAll("<", "%3C");
    needValid = needValid.replaceAll(">", "%3E");
    needValid = needValid.replaceAll("\\{","%7B" );
    needValid = needValid.replaceAll("}","%7D" );
    needValid = needValid.replaceAll("\"","%20" );
    return needValid;
  }

  public static String verifyRedirectUri(KeycloakSession session, String rootUrl, String redirectUri, Set<String> validRedirects, boolean requireRedirectUri) {
    KeycloakUriInfo uriInfo = session.getContext().getUri();
    RealmModel realm = session.getContext().getRealm();

    if (redirectUri != null) {
      try {
        // ??????????????????url????????????
        redirectUri = removeUrlSpaceParams(redirectUri);
        URI uri = URI.create(redirectUri);
        redirectUri = uri.normalize().toString();
      } catch (IllegalArgumentException cause) {
        logger.debug("Invalid redirect uri", cause);
        return null;
      } catch (Exception cause) {
        logger.debug("Unexpected error when parsing redirect uri", cause);
        return null;
      }
    }

    if (redirectUri == null) {
      if (!requireRedirectUri) {
        redirectUri = getSingleValidRedirectUri(validRedirects);
      }

      if (redirectUri == null) {
        logger.debug("No Redirect URI parameter specified");
        return null;
      }
    } else if (validRedirects.isEmpty()) {
      logger.debug("No Redirect URIs supplied");
      redirectUri = null;
    } else {
      redirectUri = lowerCaseHostname(redirectUri);

      String r = redirectUri;
      Set<String> resolveValidRedirects = resolveValidRedirects(session, rootUrl, validRedirects);

      boolean valid = matchesRedirects(resolveValidRedirects, r);

      if (!valid && (r.startsWith(Constants.INSTALLED_APP_URL) || r.startsWith(Constants.INSTALLED_APP_LOOPBACK)) && r.indexOf(':', Constants.INSTALLED_APP_URL.length()) >= 0) {
        int i = r.indexOf(':', Constants.INSTALLED_APP_URL.length());

        StringBuilder sb = new StringBuilder();
        sb.append(r.substring(0, i));

        i = r.indexOf('/', i);
        if (i >= 0) {
          sb.append(r.substring(i));
        }

        r = sb.toString();

        valid = matchesRedirects(resolveValidRedirects, r);
      }
      if (valid && redirectUri.startsWith("/")) {
        redirectUri = relativeToAbsoluteURI(session, rootUrl, redirectUri);
      }
      redirectUri = valid ? redirectUri : null;
    }

    if (Constants.INSTALLED_APP_URN.equals(redirectUri)) {
      return Urls.realmInstalledAppUrnCallback(uriInfo.getBaseUri(), realm.getName()).toString();
    } else {
      return redirectUri;
    }
  }

  private static String lowerCaseHostname(String redirectUri) {
    int n = redirectUri.indexOf('/', 7);
    if (n == -1) {
      return redirectUri.toLowerCase();
    } else {
      return redirectUri.substring(0, n).toLowerCase() + redirectUri.substring(n);
    }
  }

  private static String relativeToAbsoluteURI(KeycloakSession session, String rootUrl, String relative) {
    if (rootUrl != null) {
      rootUrl = ResolveRelative.resolveRootUrl(session, rootUrl);
    }

    if (rootUrl == null || rootUrl.isEmpty()) {
      rootUrl = UriUtils.getOrigin(session.getContext().getUri().getBaseUri());
    }
    StringBuilder sb = new StringBuilder();
    sb.append(rootUrl);
    sb.append(relative);
    return sb.toString();
  }

  private static boolean matchesRedirects(Set<String> validRedirects, String redirect) {
    for (String validRedirect : validRedirects) {
      if (validRedirect.endsWith("*") && !validRedirect.contains("?")) {
        // strip off the query component - we don't check them when wildcards are effective
        String r = redirect.contains("?") ? redirect.substring(0, redirect.indexOf("?")) : redirect;
        // strip off *
        int length = validRedirect.length() - 1;
        validRedirect = validRedirect.substring(0, length);
        if (r.startsWith(validRedirect)) return true;
        // strip off trailing '/'
        if (length - 1 > 0 && validRedirect.charAt(length - 1) == '/') length--;
        validRedirect = validRedirect.substring(0, length);
        if (validRedirect.equals(r)) return true;
      } else if (validRedirect.equals(redirect)) return true;
    }
    return false;
  }

  private static String getSingleValidRedirectUri(Collection<String> validRedirects) {
    if (validRedirects.size() != 1) return null;
    String validRedirect = validRedirects.iterator().next();
    return validateRedirectUriWildcard(validRedirect);
  }

  public static String validateRedirectUriWildcard(String redirectUri) {
    if (redirectUri == null)
      return null;

    int idx = redirectUri.indexOf("/*");
    if (idx > -1) {
      redirectUri = redirectUri.substring(0, idx);
    }
    return redirectUri;
  }

  private static String getFirstValidRedirectUri(Collection<String> validRedirects) {
    final String redirectUri = validRedirects.stream().findFirst().orElse(null);
    return (redirectUri != null) ? validateRedirectUriWildcard(redirectUri) : null;
  }

  public static String getFirstValidRedirectUri(KeycloakSession session, String rootUrl, Set<String> validRedirects) {
    return getFirstValidRedirectUri(resolveValidRedirects(session, rootUrl, validRedirects));
  }
}
