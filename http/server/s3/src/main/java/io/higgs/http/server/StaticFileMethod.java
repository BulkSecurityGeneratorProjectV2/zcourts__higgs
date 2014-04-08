package io.higgs.http.server;

import io.higgs.core.FileUtil;
import io.higgs.core.ObjectFactory;
import io.higgs.core.ResolvedFile;
import io.higgs.http.server.config.HttpConfig;
import io.higgs.http.server.protocol.HttpMethod;
import io.higgs.http.server.protocol.HttpProtocolConfiguration;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Queue;
import java.util.regex.Pattern;

/**
 * @author Courtney Robinson <courtney@crlog.info>
 */
public class StaticFileMethod extends HttpMethod {

    private static final Pattern INSECURE_URI = Pattern.compile(".*[<>&\"].*");
    public static final String HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz";
    public static final String HTTP_DATE_GMT_TIMEZONE = "GMT";
    public static final int HTTP_CACHE_SECONDS = 60;
    private final HttpProtocolConfiguration config;
    private Path base;
    private boolean canServe = true;
    private static Logger log = LoggerFactory.getLogger(StaticFileMethod.class);
    private static final Method METHOD;

    protected ResolvedFile resolvedFile;

    public StaticFileMethod(Queue<ObjectFactory> factories, HttpProtocolConfiguration protocolConfig) {
        super(factories, StaticFileMethod.class, METHOD);
        this.config = protocolConfig;
        base = Paths.get(((HttpConfig) config.getServer().getConfig()).public_directory);
        if (!Files.exists(base)) {
            canServe = false;
            log.warn("Public files directory that is configured does not exist. Will not serve static files");
        }
        setPriority(Integer.MIN_VALUE);
    }

    static {
        try {
            METHOD = StaticFileMethod.class.getMethod("getFile");
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Unable to set up static file method", e);
        }
    }

    /**
     * @return the {@link java.nio.file.Path} to the file that was found by
     * {@link #matches(String, io.netty.channel.ChannelHandlerContext, Object)}
     */
    public ResolvedFile getFile() {
        return resolvedFile;
    }

    @Override
    public boolean matches(String requestPath, ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof HttpRequest)) {
            return false;
        }
        HttpRequest request = (HttpRequest) msg;
        //three main conditions need to be met to serve a static file
        //is this a GET request?
        //does the base directory exist?
        //is the URL a subdirectory of the base?
        if (!canServe || !request.getMethod().name().equalsIgnoreCase(
                io.netty.handler.codec.http.HttpMethod.GET.name())) {
            return false;
        }
        String uri = normalizeURI(request);
        //sanitize before use
        uri = sanitizeUri(uri);
        if (uri == null) {
            return false;
        }
        Path matchedFile = Paths.get(uri);
        if (Files.isDirectory(matchedFile)) {
            //get list of files, if index/default found then send it instead of listing directory
            try {
                DirectoryStream<Path> paths = Files.newDirectoryStream(matchedFile);
                boolean list = true;
                for (Path localPath : paths) {
                    Path name = localPath.getFileName();
                    if (((HttpConfig) config.getServer().getConfig()).index_file.endsWith(name.toString())) {
                        matchedFile = name;
                        list = false;
                        break;
                    }
                }
                if (list) {
                    //directory listing not enabled return 404 or another error
                    return ((HttpConfig) config.getServer().getConfig()).enable_directory_listing;
                }
            } catch (IOException e) {
                log.info(String.format("Failed to list files in directory {%s}", e.getMessage()));
                return false;
            }
        }
        resolvedFile = FileUtil.resolve(base, matchedFile);
        if (!resolvedFile.exists()) {
            //static file method is a last resort and called after all other methods failed to match
            //if the file is found to be a static file then raise a 404
            WebApplicationException e = new WebApplicationException(HttpResponseStatus.NOT_FOUND, request, this);
            e.setMessage(resolvedFile.getName() + " not found");
            throw e;
        }
        return resolvedFile.hasStream();
    }

    public Object invoke(ChannelHandlerContext ctx, String path, Object msg, Object[] params)
            throws InvocationTargetException, IllegalAccessException, InstantiationException {
        return classMethod.invoke(this, params);
    }

    private String normalizeURI(HttpRequest request) {
        String uri = request.getUri();
        //remove query string from path
        if (uri.contains("?")) {
            uri = uri.substring(0, uri.indexOf("?"));
        }
        if (uri.equals("/") && ((HttpConfig) config.getServer().getConfig()).serve_index_file) {
            uri = ((HttpConfig) config.getServer().getConfig()).index_file;
        }
        //remove forward slash from URIs so that they're resolved relatively
        if (uri.startsWith("/")) {
            uri = uri.substring(1);
        }
        return uri;
    }

    private String sanitizeUri(String uri) {
        // Decode the path.
        try {
            uri = URLDecoder.decode(uri, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            try {
                uri = URLDecoder.decode(uri, "ISO-8859-1");
            } catch (UnsupportedEncodingException e1) {
                throw new Error();
            }
        }
        // Convert file separators.
        uri = uri.replace('/', File.separatorChar);
        // TODO provide more secure checks
        if (uri.contains(File.separator + '.') ||
                uri.contains('.' + File.separator) ||
                uri.startsWith(".") || uri.endsWith(".") ||
                INSECURE_URI.matcher(uri).matches()) {
            return null;
        }
        return uri;
    }
}
