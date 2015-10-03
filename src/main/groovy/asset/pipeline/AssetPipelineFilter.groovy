package asset.pipeline

import org.springframework.context.*
import javax.servlet.*
import javax.servlet.http.*
import org.springframework.web.context.support.WebApplicationContextUtils
import org.springframework.web.filter.*
import groovy.util.logging.Commons
import groovy.transform.*
import asset.pipeline.grails.AssetProcessorService
import asset.pipeline.AssetPipelineConfigHolder
import asset.pipeline.AssetPipelineResponseBuilder
import asset.pipeline.grails.AssetAttributes
import asset.pipeline.grails.ProductionAssetCache
import java.net.URI

@Commons
@CompileStatic
class AssetPipelineFilter extends OncePerRequestFilter {
	public static final String HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz"
	public static final ProductionAssetCache fileCache = new ProductionAssetCache();
    ApplicationContext applicationContext
    ServletContext servletContext

    @Override
    void initFilterBean() throws ServletException {
        def config = filterConfig
        applicationContext = WebApplicationContextUtils.getWebApplicationContext(config.servletContext)
        servletContext = config.servletContext
    }

    void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        boolean warDeployed = AssetPipelineConfigHolder.manifest ? true : false

        String mapping = ((AssetProcessorService)(applicationContext.getBean('assetProcessorService', AssetProcessorService))).assetMapping

        def fileUri = new URI(request.requestURI).path
        def baseAssetUrl = request.contextPath == "/" ? "/$mapping" : "${request.contextPath}/${mapping}"
        def format = servletContext.getMimeType(fileUri)
        def encoding = request.getParameter('encoding') ?: request.getCharacterEncoding()

        if(fileUri.startsWith(baseAssetUrl)) {
            fileUri = fileUri.substring(baseAssetUrl.length())
        }
        if(warDeployed) {
			def manifest = AssetPipelineConfigHolder.manifest
			def manifestPath = fileUri
			if(fileUri.startsWith('/')) {
			  manifestPath = fileUri.substring(1) //Omit forward slash
			}
			fileUri = manifest?.getProperty(manifestPath, manifestPath)

			AssetAttributes attributeCache = fileCache.get(fileUri)

			if(attributeCache) {
				def file = attributeCache.resource
				def responseBuilder = new AssetPipelineResponseBuilder(fileUri,request.getHeader('If-None-Match'))
				responseBuilder.headers.each { header ->
					response.setHeader(header.key,header.value)
				}
				if(responseBuilder.statusCode) {
					response.status = responseBuilder.statusCode
				}
				response.setHeader('Last-Modified', attributeCache.getLastModified().format(HTTP_DATE_FORMAT))
				if(responseBuilder.statusCode != 304) {
					def acceptsEncoding = request.getHeader("Accept-Encoding")
					if(acceptsEncoding?.split(",")?.contains("gzip") && attributeCache.gzipExists()) {
						file = attributeCache.getGzipResource()
						response.setHeader('Content-Encoding','gzip')
						response.setHeader('Content-Length', attributeCache.getGzipFileSize().toString())
					} else {
						response.setHeader('Content-Length', attributeCache.getFileSize().toString())
					}
					if(encoding) {
						response.setCharacterEncoding(encoding)
					}

					response.setContentType(format)

					try {
						byte[] buffer = new byte[102400];
						int len;
						def inputStream = file.inputStream
						def out = response.outputStream
						while ((len = inputStream.read(buffer)) != -1) {
							out.write(buffer, 0, len);
						}
						response.flushBuffer()
					} catch(e) {
						log.debug("File Transfer Aborted (Probably by the user)",e)
					}
				} else {
					response.flushBuffer()
				}
			} else {
				def file = applicationContext.getResource("assets/${fileUri}")
				if(!file.exists()) {
					file = applicationContext.getResource("classpath:assets/${fileUri}")
				}

				if(file.exists()) {
					def responseBuilder = new AssetPipelineResponseBuilder(fileUri,request.getHeader('If-None-Match'))
					responseBuilder.headers.each { header ->
						response.setHeader(header.key,header.value)
					}
					if(responseBuilder.statusCode) {
						response.status = responseBuilder.statusCode
					}
					response.setHeader('Last-Modified', new Date(file.lastModified()).format(HTTP_DATE_FORMAT))

					def gzipFile = applicationContext.getResource("assets/${fileUri}.gz")
					if(!gzipFile.exists()) {
						gzipFile = applicationContext.getResource("classpath:assets/${fileUri}.gz")
					}

					AssetAttributes newCache = new AssetAttributes(true, gzipFile.exists(), false, file.contentLength(), gzipFile.contentLength(), new Date(file.lastModified()), file, gzipFile)
					fileCache.put(fileUri, newCache)

					if(responseBuilder.statusCode != 304) {
						// Check for GZip
						def acceptsEncoding = request.getHeader("Accept-Encoding")
						if(acceptsEncoding?.split(",")?.contains("gzip")) {
							if(gzipFile.exists()) {
								file = gzipFile
								response.setHeader('Content-Encoding','gzip')
							}
						}
						if(encoding) {
							response.setCharacterEncoding(encoding)
						}
						response.setContentType(format)
						response.setHeader('Content-Length', file.contentLength().toString())

						try {
							byte[] buffer = new byte[102400];
							int len;
							def inputStream = file.inputStream
							def out = response.outputStream
							while ((len = inputStream.read(buffer)) != -1) {
								out.write(buffer, 0, len);
							}
							response.flushBuffer()
						} catch(e) {
							log.debug("File Transfer Aborted (Probably by the user)",e)
						}
					} else {
						response.flushBuffer()
					}
				} else {
					AssetAttributes newCache = new AssetAttributes(false, false, false, null, null, null, null, null)
					fileCache.put(fileUri, newCache)
					response.status = 404
					response.flushBuffer()
				}
			}
        } else {
            def fileContents
            if(request.getParameter('compile') == 'false') {
                fileContents = AssetPipeline.serveUncompiledAsset(fileUri,format, null, encoding)
            } else {
                fileContents = AssetPipeline.serveAsset(fileUri,format, null, encoding)
            }

            if (fileContents != null) {

                response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate") // HTTP 1.1.
                response.setHeader("Pragma", "no-cache") // HTTP 1.0.
                response.setDateHeader("Expires", 0) // Proxies.
                response.setHeader('Content-Length', fileContents.size().toString())

                response.setContentType(format)
                try {
                    response.outputStream << fileContents
                    response.flushBuffer()
                } catch(e) {
                    log.debug("File Transfer Aborted (Probably by the user)",e)
                }
            } else {
                response.status = 404
                response.flushBuffer()
            }
        }

        if (!response.committed) {
            chain.doFilter(request, response)
        }
    }
}
