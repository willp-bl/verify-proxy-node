package uk.gov.ida.notification.translator.apprule;

import org.apache.http.HttpStatus;
import org.bouncycastle.util.Strings;
import org.glassfish.jersey.internal.util.Base64;
import org.junit.Before;
import org.junit.Test;
import org.opensaml.core.xml.io.MarshallingException;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.EncryptedAssertion;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.security.credential.BasicCredential;
import org.opensaml.security.credential.Credential;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.support.SignatureConstants;
import org.opensaml.xmlsec.signature.support.SignatureException;
import org.w3c.dom.Element;
import uk.gov.ida.notification.translator.apprule.base.TranslatorAppRuleTestBase;
import uk.gov.ida.notification.helpers.HubAssertionBuilder;
import uk.gov.ida.notification.helpers.HubResponseBuilder;
import uk.gov.ida.notification.pki.KeyPairConfiguration;
import uk.gov.ida.notification.saml.ResponseAssertionDecrypter;
import uk.gov.ida.notification.saml.SamlFormMessageType;
import uk.gov.ida.notification.saml.SamlObjectMarshaller;
import uk.gov.ida.notification.saml.SamlParser;
import uk.gov.ida.saml.core.test.TestCredentialFactory;
import uk.gov.ida.saml.core.test.TestEntityIds;
import uk.gov.ida.saml.core.test.builders.ResponseBuilder;
import uk.gov.ida.saml.security.CredentialFactorySignatureValidator;
import uk.gov.ida.saml.security.SignatureValidator;
import uk.gov.ida.saml.security.SigningCredentialFactory;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Form;
import java.io.ByteArrayInputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static uk.gov.ida.saml.core.test.TestCertificateStrings.STUB_IDP_PUBLIC_PRIMARY_CERT;
import static uk.gov.ida.saml.core.test.TestCertificateStrings.STUB_IDP_PUBLIC_PRIMARY_PRIVATE_KEY;
import static uk.gov.ida.saml.core.test.TestCertificateStrings.TEST_RP_PRIVATE_ENCRYPTION_KEY;
import static uk.gov.ida.saml.core.test.TestCertificateStrings.TEST_RP_PUBLIC_ENCRYPTION_CERT;

public class HubResponseFromGatewayAppRuleTests extends TranslatorAppRuleTestBase {
    private static final String PROXY_NODE_ENTITY_ID = "http://proxy-node.uk";
    private static final String BEGIN_CERT = "-----BEGIN CERTIFICATE-----\n";
    private static final String END_CERT = "\n-----END CERTIFICATE-----";
    private SamlObjectMarshaller marshaller;
    private BasicCredential hubSigningCredential;
    private EncryptedAssertion authnAssertion;
    private EncryptedAssertion matchingDatasetAssertion;

    @Before
    public void setup() throws Throwable {
        KeyPairConfiguration hubFacingEncryptionKeyPair = translatorAppRule.getConfiguration().getHubFacingEncryptionKeyPair();
        Credential hubAssertionsEncryptionCredential = new BasicCredential(
            hubFacingEncryptionKeyPair.getPublicKey().getPublicKey()
        );
        marshaller = new SamlObjectMarshaller();

        String publicCert = BEGIN_CERT + STUB_IDP_PUBLIC_PRIMARY_CERT + END_CERT;
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(publicCert.getBytes(StandardCharsets.UTF_8));
        X509Certificate x509certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(byteArrayInputStream);
        PublicKey publicKey = x509certificate.getPublicKey();
        PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(Strings.toByteArray(STUB_IDP_PUBLIC_PRIMARY_PRIVATE_KEY))));

        hubSigningCredential = new BasicCredential(publicKey, privateKey);
        authnAssertion = HubAssertionBuilder.anAuthnStatementAssertion()
            .withSignature(hubSigningCredential, STUB_IDP_PUBLIC_PRIMARY_CERT)
            .withIssuer(TestEntityIds.STUB_IDP_ONE)
            .withSubject(PROXY_NODE_ENTITY_ID, ResponseBuilder.DEFAULT_REQUEST_ID)
            .buildEncrypted(hubAssertionsEncryptionCredential);
        matchingDatasetAssertion = HubAssertionBuilder.aMatchingDatasetAssertion()
            .withSignature(hubSigningCredential, STUB_IDP_PUBLIC_PRIMARY_CERT)
            .withIssuer(TestEntityIds.STUB_IDP_ONE)
            .withSubject(PROXY_NODE_ENTITY_ID, ResponseBuilder.DEFAULT_REQUEST_ID)
            .buildEncrypted(hubAssertionsEncryptionCredential);
    }

    @Test
    public void shouldReturnASignedEidasResponse() throws Exception {
        KeyPairConfiguration signingKeyPair = translatorAppRule.getConfiguration().getConnectorFacingSigningKeyPair();
        SignatureValidator signatureValidator = new CredentialFactorySignatureValidator(new SigningCredentialFactory(entityId -> singletonList(signingKeyPair.getPublicKey().getPublicKey())));

        Response eidasResponse = extractEidasResponse(buildSignedHubResponse());

        Signature signature = eidasResponse.getSignature();

        assertNotNull("SAML Response needs to be signed", signature);
        assertTrue("Invalid signature", signatureValidator.validate(eidasResponse, null, Response.DEFAULT_ELEMENT_NAME));
        assertEquals(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256, signature.getSignatureAlgorithm());
    }

    @Test
    public void shouldReturnAnEncryptedEidasResponse() throws Exception {
        Response eidasResponse = extractEidasResponse(buildSignedHubResponse());
        assertEquals(1, eidasResponse.getEncryptedAssertions().size());
        assert(eidasResponse.getAssertions().isEmpty());
    }

    @Test
    public void postingHubResponseShouldReturnEidasResponseForm() throws Exception {
        Response hubResponse = buildSignedHubResponse();
        Credential decryptingCredential = new TestCredentialFactory(TEST_RP_PUBLIC_ENCRYPTION_CERT, TEST_RP_PRIVATE_ENCRYPTION_KEY).getDecryptingCredential();
        Response eidasResponse = extractEidasResponse(hubResponse);
        Response decryptedEidasResponse = decryptResponse(eidasResponse, decryptingCredential);
        Assertion eidasAssertion = decryptedEidasResponse.getAssertions().get(0);
        Element attributeStatement = marshaller.marshallToElement(eidasAssertion.getAttributeStatements().get(0));

        assertEquals(hubResponse.getInResponseTo(), eidasResponse.getInResponseTo());
        assertEquals(1, eidasAssertion.getAttributeStatements().size());
        assertEquals(1, eidasAssertion.getAuthnStatements().size());

        assertEquals("Jazzy Harold", attributeStatement.getFirstChild().getTextContent());
    }

    @Test
    public void shouldNotAcceptUnsignedHubResponse() throws Exception {
        javax.ws.rs.core.Response response = postHubResponseToTranslator(buildUnsignedHubResponse());
        String message = response.readEntity(String.class);
        assertEquals(response.getStatus(), HttpStatus.SC_BAD_REQUEST);
        assertThat(message).contains("Error handling hub response");
    }

    @Test
    public void shouldValidateHubResponseMessage() throws Exception {
        Response invalidResponse = getHubResponseBuilder()
            .withIssuer(null)
            .buildSigned(hubSigningCredential);

        javax.ws.rs.core.Response response = postHubResponseToTranslator(invalidResponse);
        String message = response.readEntity(String.class);

        assertEquals(HttpStatus.SC_BAD_REQUEST, response.getStatus());
        assertThat(message).contains("Error handling hub response");
    }

    private Response extractEidasResponse(Response hubResponse) throws Exception {
        String decodedEidasResponse = postHubResponseToTranslator(hubResponse).readEntity(String.class);
        return new SamlParser().parseSamlString(decodedEidasResponse);
    }

    private javax.ws.rs.core.Response postHubResponseToTranslator(Response hubResponse) throws URISyntaxException {
        String encodedResponse = Base64.encodeAsString(marshaller.transformToString(hubResponse));
        Form postForm = new Form().param(SamlFormMessageType.SAML_RESPONSE, encodedResponse);

        return translatorAppRule
                .target("/SAML2/SSO/Response/POST")
                .request()
                .post(Entity.form(postForm));
    }

    private static Response decryptResponse(Response response, Credential credential) {
        ResponseAssertionDecrypter decrypter = new ResponseAssertionDecrypter(credential);
        return decrypter.decrypt(response);
    }

    private Response buildSignedHubResponse() throws MarshallingException, SignatureException {
        return getHubResponseBuilder().buildSigned(hubSigningCredential);
    }

    private Response buildUnsignedHubResponse() throws MarshallingException, SignatureException {
        return getHubResponseBuilder().build();
    }

    private HubResponseBuilder getHubResponseBuilder() {
        return new HubResponseBuilder()
                .withIssuer(TestEntityIds.STUB_IDP_ONE)
                .withDestination("http://proxy-node/SAML2/SSO/Response")
                .addEncryptedAssertion(authnAssertion)
                .addEncryptedAssertion(matchingDatasetAssertion);
    }
}
