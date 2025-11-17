package com.medify.medicamentos_backend.service;
import com.dropbox.core.DbxException;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.WriteMode;
import com.dropbox.core.v2.sharing.SharedLinkMetadata;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DropboxService {

    private static final Logger log = LoggerFactory.getLogger(DropboxService.class);
    private static final List<String> ALLOWED_CONTENT_TYPES = Arrays.asList(
            "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp"
    );

    @Value("${dropbox.access.token:}")
    private String accessToken;

    @Value("${dropbox.folder.path:/medify/imagenes}")
    private String folderPath;

    private DbxClientV2 client;
    private volatile boolean dropboxConfigured = false;

    @PostConstruct
    public void init() {
        if (accessToken == null || accessToken.isBlank()) {
            log.warn("Dropbox access token no configurado. Las subidas de imágenes no funcionarán.");
            return;
        }

        try {
            DbxRequestConfig config = DbxRequestConfig.newBuilder("medify-backend").build();
            client = new DbxClientV2(config, accessToken);

            // Test de conexión
            client.users().getCurrentAccount();
            dropboxConfigured = true;
            log.info("Dropbox configurado correctamente");
        } catch (DbxException e) {
            log.error("Error inicializando Dropbox: {}", e.getMessage());
        }
    }

    public boolean isConfigured() {
        return dropboxConfigured;
    }

    /**
     * Sube una imagen a Dropbox y retorna el link y el path
     * @return Map con "url" y "path"
     */
    public Map<String, String> subirImagen(MultipartFile file, String carpeta) throws IOException, DbxException {

        validarArchivo(file);

        String fileName = generarNombreUnico(file.getOriginalFilename());
        String dropboxPath = construirRuta(carpeta, fileName);

        log.info("Subiendo imagen a Dropbox: {}", dropboxPath);

        // Subir archivo
        try (InputStream in = file.getInputStream()) {
            FileMetadata metadata = client.files()
                    .uploadBuilder(dropboxPath)
                    .withMode(WriteMode.ADD)
                    .uploadAndFinish(in);

            log.info("Imagen subida exitosamente: {}", metadata.getPathDisplay());
        }

        // Obtener link público
        String publicUrl = obtenerLinkPublico(dropboxPath);
        log.info("Link generado: {}", publicUrl);

        // Retornar tanto la URL como el path
        Map<String, String> resultado = new HashMap<>();
        resultado.put("url", publicUrl);
        resultado.put("path", dropboxPath);

        return resultado;
    }

    /**
     * Elimina una imagen de Dropbox
     */
    public void eliminarImagen(String dropboxPath) throws DbxException {
        log.info("Eliminando imagen de Dropbox: {}", dropboxPath);
        client.files().deleteV2(dropboxPath);
        log.info("Imagen eliminada exitosamente");
    }

    /**
     * Valida el archivo antes de subirlo
     */
    private void validarArchivo(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("El archivo está vacío");
        }

        if (file.getSize() > 20 * 1024 * 1024) { // 10MB
            throw new IllegalArgumentException("El archivo excede el tamaño máximo de 10MB");
        }

        String contentType = file.getContentType();
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException(
                    "Tipo de archivo no permitido: " + contentType +
                            ". Permitidos: " + ALLOWED_CONTENT_TYPES
            );
        }
    }

    /**
     * Genera un nombre para el archivo
     */
    private String generarNombreUnico(String originalFilename) {
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }

        return timestamp + "_" + System.nanoTime() + extension;
    }

    /**
     * Construye la ruta completa en Dropbox
     */
    private String construirRuta(String carpeta, String fileName) {
        String path = folderPath;
        if (carpeta != null && !carpeta.isBlank()) {
            path = path + "/" + carpeta;
        }
        return path + "/" + fileName;
    }

    /**
     * Obtiene o crea un link para el archivo
     */
    private String obtenerLinkPublico(String dropboxPath) throws DbxException {
        try {
            // Intentar obtener link existente
            List<SharedLinkMetadata> links = client.sharing()
                    .listSharedLinksBuilder()
                    .withPath(dropboxPath)
                    .withDirectOnly(true)
                    .start()
                    .getLinks();

            if (!links.isEmpty()) {
                return convertirALinkDirecto(links.get(0).getUrl());
            }

            // Crear nuevo link compartido
            SharedLinkMetadata sharedLink = client.sharing()
                    .createSharedLinkWithSettings(dropboxPath);

            return convertirALinkDirecto(sharedLink.getUrl());

        } catch (Exception e) {
            log.error("Error obteniendo link: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Convierte el link de Dropbox a link directo para mostrar imagen
     */
    private String convertirALinkDirecto(String dropboxUrl) {
        // Cambiar ?dl=0 por ?raw=1 para link directo
        return dropboxUrl.replace("?dl=0", "?raw=1")
                .replace("www.dropbox.com", "dl.dropboxusercontent.com");
    }
}
