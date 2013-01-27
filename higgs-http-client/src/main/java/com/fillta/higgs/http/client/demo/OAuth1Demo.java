package com.fillta.higgs.http.client.demo;

import com.fillta.functional.Function1;
import com.fillta.higgs.events.listeners.ChannelEventListener;
import com.fillta.higgs.http.client.HTTPResponse;
import com.fillta.higgs.http.client.HttpRequestBuilder;
import com.fillta.higgs.http.client.oauth.v1.OAuth1AccessToken;
import com.fillta.higgs.http.client.oauth.v1.OAuth1RequestToken;
import com.google.common.base.Optional;
import io.netty.channel.ChannelHandlerContext;
import org.scribe.builder.api.TwitterApi;
import org.scribe.model.SignatureType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class OAuth1Demo {
    protected OAuth1Demo() {
    }

    public static void main(String... args) throws IOException, InterruptedException {
        String key = "6icbcAXyZx67r8uTAUM5Qw";
        String secret = "SCCAdUUc6LXxiazxH3N0QfpNUvlUy84mZ2XZKiv39s";
        final HttpRequestBuilder builder = new HttpRequestBuilder();
        builder.getRequester().onException(new ChannelEventListener() {
            public void triggered(final ChannelHandlerContext ctx, final Optional<Throwable> ex) {
                ex.get().printStackTrace();
            }
        });
        final OAuth1RequestToken[] requestToken = new OAuth1RequestToken[1];
        builder
                .oauth1() //switch to oAuth v1 builder
                        //copy the oauth_verifier from the url when Twitter redirects you
                .config(TwitterApi.class, key, secret, "http://localhost/app/auth/twitter")
                        //get a request token
                .requestToken(new Function1<OAuth1RequestToken>() {
                    public void apply(OAuth1RequestToken token) {
                        requestToken[0] = token;
                        System.out.println(token.authorizationUrl());
                    }
                });
        String verifier;
        while ((verifier = new BufferedReader(new InputStreamReader(System.in)).readLine()).isEmpty()) {
            System.out.println("Enter a non-empty verifier");
        }
        builder.oauth1().accessToken(requestToken[0], verifier, new Function1<OAuth1AccessToken>() {
            public void apply(OAuth1AccessToken a) {
                System.out.println("Access token:\n" + a);
                builder
                        .GET()
                        .url("https://api.twitter.com/1.1/statuses/home_timeline.json")
//                        .form("status", "Testing the API " + new Date())
                                //switch to OAuth Builder ONLY AFTER YOU'VE FINISHED ADDING PARAMS TO THE REQUEST
                                //all added params will be included in the signing process
                        .oauth1()
                        .signature(SignatureType.Header) //optional, can be header (default) or query string
                        .signRequest(a, new Function1<HTTPResponse>() {
                            public void apply(HTTPResponse a) {
                                a.readAll(new Function1<String>() {
                                    public void apply(String response) {
                                        System.out.println(response);
                                    }
                                });
                            }
                        });
            }
        });
    }
}
