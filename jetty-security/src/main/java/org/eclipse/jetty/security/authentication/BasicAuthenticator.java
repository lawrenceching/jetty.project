// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.security.authentication;

import java.io.IOException;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.security.B64Code;
import org.eclipse.jetty.http.security.Constraint;
import org.eclipse.jetty.security.Authentication;
import org.eclipse.jetty.security.DefaultAuthentication;
import org.eclipse.jetty.security.DefaultUserIdentity;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.StringUtil;

/**
 * @version $Rev: 4793 $ $Date: 2009-03-19 00:00:01 +0100 (Thu, 19 Mar 2009) $
 */
public class BasicAuthenticator extends LoginAuthenticator 
{
    /* ------------------------------------------------------------ */
    /**
     * @param loginService
     */
    public BasicAuthenticator()
    {
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.security.Authenticator#getAuthMethod()
     */
    public String getAuthMethod()
    {
        return Constraint.__BASIC_AUTH;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.security.Authenticator#validateRequest(javax.servlet.ServletRequest, javax.servlet.ServletResponse, boolean)
     */
    public Authentication validateRequest(ServletRequest req, ServletResponse res, boolean mandatory) throws ServerAuthException
    {
        HttpServletRequest request = (HttpServletRequest)req;
        HttpServletResponse response = (HttpServletResponse)res;
        String credentials = request.getHeader(HttpHeaders.AUTHORIZATION);

        try
        {
            if (credentials != null)
            {                  
                credentials = credentials.substring(credentials.indexOf(' ')+1);
                credentials = B64Code.decode(credentials,StringUtil.__ISO_8859_1);
                int i = credentials.indexOf(':');
                String username = credentials.substring(0,i);
                String password = credentials.substring(i+1);
                
                UserIdentity user = _loginService.login(username,password);
                if (user!=null)
                {
                    if (user instanceof DefaultUserIdentity)
                        return ((DefaultUserIdentity)user).SUCCESSFUL_BASIC;
                    return new DefaultAuthentication(Authentication.Status.SUCCESS,Constraint.__BASIC_AUTH,user);
                }
            }

            if (!mandatory) 
            {
                return DefaultAuthentication.SUCCESS_UNAUTH_RESULTS;
            }
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "basic realm=\"" + _loginService.getName() + '"');
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return DefaultAuthentication.SEND_CONTINUE_RESULTS;
        }
        catch (IOException e)
        {
            throw new ServerAuthException(e);
        }
    }

    // most likely validatedUser is not needed here.

    // corrct?
    public Authentication.Status secureResponse(ServletRequest req, ServletResponse res, boolean mandatory, Authentication validatedUser) throws ServerAuthException
    {
        return Authentication.Status.SUCCESS;
    }

}
