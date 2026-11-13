package org.openfilz.dms.service.branding;

/**
 * Where every surface reads the product brand (e-mails, generated documents). The default
 * implementation reads the deployment properties ({@link PropertiesBrandingProvider}); an extension
 * layer may declare its own {@code @Primary} bean that reads the brand from elsewhere.
 * <p>
 * Called per message, from any thread: an implementation answers from memory, never blocking IO.
 */
public interface BrandingProvider {

    Brand brand();

    /**
     * The brand's logo as PNG bytes, for generated documents (the completion certificate of a signed
     * document); null = no logo. The default has none: a deployment that only sets a logo address
     * gives nothing to fetch here, since generating a document never calls out to the network.
     */
    default byte[] logoPng() {
        return null;
    }
}
