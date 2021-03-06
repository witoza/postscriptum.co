package co.postscriptum.security;

import co.postscriptum.RuntimeEnvironment;
import co.postscriptum.exception.InternalException;
import co.postscriptum.internal.QRGenerator;
import co.postscriptum.internal.Utils;
import co.postscriptum.model.bo.UserData;
import lombok.AllArgsConstructor;
import org.apache.commons.codec.binary.Hex;
import org.jboss.security.otp.TimeBasedOTPUtil;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.security.GeneralSecurityException;

@AllArgsConstructor
@Service
public class TOTPHelperService {

    private final RuntimeEnvironment env;

    public boolean isTokenValid(UserData userData, String totpToken) {
        String totpSecret = Hex.encodeHexString(userData.getInternal().getTotpSecret());
        try {
            return TimeBasedOTPUtil.validate(totpToken, totpSecret.getBytes(), 6);
        } catch (GeneralSecurityException e) {
            throw new InternalException("Can't calculate security token", e);
        }
    }

    public BufferedImage getTotpUriQr(UserData userData) {
        try {
            return QRGenerator.createQr(getTotpUri(userData));
        } catch (Exception e) {
            throw new InternalException("Exception occurred while generating QR code with TOTP details", e);
        }
    }

    public String getTotpUri(UserData userData) {

        final String issuer = env.getDomain();

        return "otpauth://totp/" + issuer + ":" + Utils.urlEncode(userData.getUser().getUsername()) + "?secret="
                + Utils.base32encode(userData.getInternal().getTotpSecret()) + "&issuer=" + issuer +
                "&algorithm=SHA1&digits=6&period=30";
    }

}
