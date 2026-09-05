package peergos.android.sync;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.NetworkErrorException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import peergos.android.MainActivity;

/**
 * Minimal authenticator: enough for the account to exist and for Settings to show the
 * calendar toggle, and nothing more.
 *
 * There are no tokens to hand out because nothing asks this for credentials — the sync
 * adapter gets its session from the app's stored MountConfig. Adding an account is
 * therefore not a login prompt of its own; it sends the user to the app, which is where
 * signing in already happens.
 */
public class PeergosAuthenticator extends AbstractAccountAuthenticator {

    private final Context context;

    public PeergosAuthenticator(Context context) {
        super(context);
        this.context = context;
    }

    @Override
    public Bundle addAccount(AccountAuthenticatorResponse response, String accountType,
                             String authTokenType, String[] requiredFeatures, Bundle options) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra(android.accounts.AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response);
        Bundle result = new Bundle();
        result.putParcelable(android.accounts.AccountManager.KEY_INTENT, intent);
        return result;
    }

    @Override
    public Bundle editProperties(AccountAuthenticatorResponse response, String accountType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Bundle confirmCredentials(AccountAuthenticatorResponse response, Account account,
                                     Bundle options) {
        return null;
    }

    @Override
    public Bundle getAuthToken(AccountAuthenticatorResponse response, Account account,
                               String authTokenType, Bundle options) throws NetworkErrorException {
        throw new UnsupportedOperationException("Peergos accounts do not issue auth tokens");
    }

    @Override
    public String getAuthTokenLabel(String authTokenType) {
        return null;
    }

    @Override
    public Bundle updateCredentials(AccountAuthenticatorResponse response, Account account,
                                    String authTokenType, Bundle options) {
        return null;
    }

    @Override
    public Bundle hasFeatures(AccountAuthenticatorResponse response, Account account,
                              String[] features) {
        Bundle result = new Bundle();
        result.putBoolean(android.accounts.AccountManager.KEY_BOOLEAN_RESULT, false);
        return result;
    }
}
