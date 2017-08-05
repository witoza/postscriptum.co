package co.postscriptum.service;

import co.postscriptum.controller.PreviewController;
import co.postscriptum.db.Account;
import co.postscriptum.db.DB;
import co.postscriptum.exception.BadRequestException;
import co.postscriptum.exception.ForbiddenException;
import co.postscriptum.internal.FileEncryptionService;
import co.postscriptum.internal.MessageContentUtils;
import co.postscriptum.internal.Utils;
import co.postscriptum.model.BO2DTOConverter;
import co.postscriptum.model.bo.File;
import co.postscriptum.model.bo.Message;
import co.postscriptum.model.bo.ReleaseItem;
import co.postscriptum.model.bo.UserData;
import co.postscriptum.model.dto.MessageDTO;
import co.postscriptum.security.AESGCMUtils;
import co.postscriptum.security.AESKeyUtils;
import co.postscriptum.security.UserEncryptionKeyService;
import co.postscriptum.web.AuthenticationHelper;
import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
public class PreviewService {

    private final FileEncryptionService fileEncryptionService;

    private final DB db;

    private final UserEncryptionKeyService userEncryptionKeyService;

    //can be invoked from normal mode and from preview
    public ResponseEntity<InputStreamResource> download(PreviewController.DownloadFileDTO form) throws IOException {

        Account fileAccount = db.requireAccountByUuid(form.getUser_uuid());
        try {
            fileAccount.lock();
            db.loadAccount(fileAccount);

            UserData userData = fileAccount.getUserData();

            UserDataHelper userDataHelper = new UserDataHelper(userData);

            File file = userDataHelper.requireFileByUuid(form.getFile_uuid());

            log.info("Downloading file name: {}, uuid: {}", file.getName(), file.getUuid());

            SecretKey encryptionKey;

            if (!StringUtils.isEmpty(form.getMsg_uuid())) {

                log.info("Preview mode download");

                Message message = userDataHelper.requireMessageByUuid(form.getMsg_uuid());

                if (!message.getAttachments().contains(file.getUuid())) {
                    throw new BadRequestException("File does not belong to that message");
                }

                ReleaseItemWithKey releaseItem = verifyCanPreviewMessage(fileAccount,
                                                                         message,
                                                                         form.getReleaseKey(),
                                                                         form.getEncryptionKey(),
                                                                         form.getRecipientKey());

                encryptionKey = releaseItem.getEncryptionKey();

                if (encryptionKey == null) {
                    throw new BadRequestException("Expected encrypted message");
                }

            } else {

                log.info("Edit mode download");

                if (!AuthenticationHelper.isUserLogged(fileAccount.getUserData().getUser().getUsername())) {
                    throw new ForbiddenException("File does not belong to the logged account");
                }

                encryptionKey = userEncryptionKeyService.requireEncryptionKey();

            }

            InputStream cfin = fileEncryptionService.getDecryptedFileStream(file,
                                                                            userData.getUser(),
                                                                            encryptionKey,
                                                                            form.getEncryptionPassword());


            String fileName = file.getName();

            String extension = FilenameUtils.getExtension(file.getName());
            if (StringUtils.isEmpty(extension)) {
                if (file.getMime().equals("video/webm")) {
                    fileName = fileName + ".webm";
                } else {
                    log.warn("Possible issue with download file content type");
                }
            }

            return ResponseEntity
                    .ok()
                    .header(HttpHeaders.CONTENT_TYPE, file.getMime())
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .body(new InputStreamResource(cfin));

        } finally {
            fileAccount.unlock();
        }
    }

    private SecretKey getEncryptionKey(String aesKey) {
        if (StringUtils.isEmpty(aesKey)) {
            return null;
        }

        try {
            return AESKeyUtils.toSecretKey(Utils.base32decode(aesKey));
        } catch (Exception e) {
            log.warn("Provided data is not a proper AES key: {}", Utils.exceptionInfo(e));
        }
        return null;
    }

    private ReleaseItemWithKey verifyCanPreviewMessage(Account messageAccount,
                                                       Message message,
                                                       String releaseKey,
                                                       String userEncryptionKey,
                                                       String recipientKey) {

        UserDataHelper userDataHelper = new UserDataHelper(messageAccount.getUserData());

        if (AuthenticationHelper.isUserLogged(messageAccount.getUserData().getUser().getUsername())) {

            log.info("Logged account is the owner of the message");

            ReleaseItem releaseItem = new ReleaseItem();
            releaseItem.setFirstTimeAccess(System.currentTimeMillis());
            releaseItem.setRecipient(StringUtils.join(message.getRecipients(), ", "));

            SecretKey secretKey = userEncryptionKeyService.getEncryptionKey()
                                                          .orElseGet(() -> getEncryptionKey(userEncryptionKey));

            return new ReleaseItemWithKey(releaseItem, secretKey);

        }

        if (message.getRelease() == null) {
            throw new ForbiddenException("Message hasn't been yet released");
        }

        ReleaseItem releaseItem = userDataHelper.requireReleaseItem(message, releaseKey);

        if (releaseItem.getFirstTimeAccess() == 0) {
            releaseItem.setFirstTimeAccess(System.currentTimeMillis());

            userDataHelper.addNotification(
                    "Message '" + message.getTitle() + "' has been first time accessed by " + releaseItem.getRecipient());
        }

        if (releaseItem.getUserEncryptionKeyEncodedByRecipientKey() == null) {

            log.info("No EncryptionKey in UserData, getting one from user input params");

            return new ReleaseItemWithKey(releaseItem, getEncryptionKey(userEncryptionKey));

        } else {

            log.info("Decrypting User's EncryptionKey by RecipientKey");

            SecretKey recipientSecretKey = AESKeyUtils.toSecretKey(Utils.base64decode(recipientKey));

            SecretKey userKey = AESKeyUtils.toSecretKey(AESGCMUtils.decrypt(recipientSecretKey,
                                                                            releaseItem.getUserEncryptionKeyEncodedByRecipientKey()));

            return new ReleaseItemWithKey(releaseItem, userKey);

        }

    }

    public MessageDTO decrypt(PreviewController.PreviewBaseDTO params) {

        return db.withLoadedAccountByUuid(params.getUser_uuid(), account -> {

            UserData userData = account.getUserData();

            Message message = new UserDataHelper(userData).requireMessageByUuid(params.getMsg_uuid());

            if (message.getEncryption() == null) {
                throw new BadRequestException("Expected encrypted message");
            }

            ReleaseItemWithKey releaseItem = verifyCanPreviewMessage(account,
                                                                     message,
                                                                     params.getReleaseKey(),
                                                                     params.getEncryptionKey(),
                                                                     params.getRecipientKey());

            SecretKey encryptionKey = releaseItem.getEncryptionKey();
            if (encryptionKey == null) {
                throw new BadRequestException("Need to have encryption key to decrypt message");
            }

            MessageDTO mdto = BO2DTOConverter.toMessageDTO(message);
            mdto.setReleaseItem(releaseItem.releaseItem);
            try {
                mdto.setContent(MessageContentUtils.getMessageContent(message, encryptionKey, params.getEncryptionPassword()));
            } catch (Exception e) {
                throw new ForbiddenException("Invalid password", e);
            }
            setFiles(mdto, userData);

            return mdto;

        });

    }

    private byte[] decryptFirstLayer(Message message, SecretKey encryptionKey) {
        return AESGCMUtils.decrypt(encryptionKey, message.getContent(), MessageContentUtils.aads(message));
    }

    public Map<String, Object> requireMessage(PreviewController.PreviewBaseDTO params) {

        return db.withLoadedAccountByUuid(params.getUser_uuid(), account -> {
            UserData userData = account.getUserData();

            Message message = new UserDataHelper(userData).requireMessageByUuid(params.getMsg_uuid());

            ReleaseItemWithKey releaseItem = verifyCanPreviewMessage(account, message,
                                                                     params.getReleaseKey(),
                                                                     params.getEncryptionKey(),
                                                                     params.getRecipientKey());

            MessageDTO mdto = BO2DTOConverter.toMessageDTO(message);
            mdto.setReleaseItem(releaseItem.getReleaseItem());

            boolean encryptionKeyValid = false;

            if (releaseItem.getEncryptionKey() != null) {

                byte[] raw = null;
                try {

                    raw = decryptFirstLayer(message, releaseItem.getEncryptionKey());
                    encryptionKeyValid = true;

                } catch (Exception e) {
                    log.warn("Invalid EncryptionKey: {}", Utils.exceptionInfo(e));
                }

                // 'content' should be set only when message not password protected
                if (message.getEncryption() == null) {
                    mdto.setContent(Utils.asString(raw));
                    setFiles(mdto, userData);
                }

            }

            Map<String, Object> data = new HashMap<>();
            data.put("msg", mdto);
            data.put("invalid_aes_key", !encryptionKeyValid);
            data.put("from", userData.getInternal().getScreenName() + " (" + userData.getUser().getUsername() + ")");
            data.put("lang", ObjectUtils.firstNonNull(message.getLang(), userData.getInternal().getLang()));
            return data;
        });

    }

    private void setFiles(MessageDTO mdto, UserData userData) {
        mdto.setFiles(userData.getFiles()
                              .stream()
                              .filter(f -> mdto.getAttachments().contains(f.getUuid()))
                              .map(f -> BO2DTOConverter.toFileDTO(userData, f))
                              .collect(Collectors.toList()));

    }

    @Value
    private static class ReleaseItemWithKey {

        ReleaseItem releaseItem;

        SecretKey encryptionKey;

    }

}
