package org.openfilz.dms.service.signature.impl;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers;
import org.bouncycastle.cms.CMSTypedData;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Adapts the PDFBox signing {@link InputStream} (the byte range to sign) to a Bouncy Castle {@link CMSTypedData}. */
record CmsInputStreamData(InputStream in) implements CMSTypedData {

    @Override
    public ASN1ObjectIdentifier getContentType() {
        return CMSObjectIdentifiers.data;
    }

    @Override
    public Object getContent() {
        return in;
    }

    @Override
    public void write(OutputStream out) throws IOException {
        in.transferTo(out);
    }
}
