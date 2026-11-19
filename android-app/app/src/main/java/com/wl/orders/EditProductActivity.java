package com.wl.orders;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONException;
import org.json.JSONObject;
import java.math.BigDecimal;

// "Edit Product": opened by the pencil icon on a Home product tile. Loads the product from
// get_product.php and saves title, original price, discounted price and delivery days back
// into the `products` table through update_product.php. Nothing but your own server is used.
public class EditProductActivity extends AppCompatActivity {

    // In: which product to edit (the name is shown while the rest loads).
    public static final String EXTRA_PRODUCT_ID = "product_id";
    public static final String EXTRA_PRODUCT_NAME = "product_name";
    // Out (setResult RESULT_OK): what Home needs to refresh the tile.
    public static final String RESULT_PRODUCT_ID = "product_id";
    public static final String RESULT_TITLE = "title";
    public static final String RESULT_PRICE = "price";

    private EditText titleInput;
    private EditText originalInput;
    private EditText discountInput;
    private EditText daysInput;
    private TextView saveButton;
    private TextView loadMessage;
    private View formGroup;

    private OrdersManager ordersManager;
    private SessionManager sessionManager;

    private int productId;
    private boolean daysIsNumber = true;
    private boolean loaded = false;
    private boolean saving = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_product);

        sessionManager = new SessionManager(this);
        if (!sessionManager.isLoggedIn()) {
            goToLogin();
            return;
        }
        productId = getIntent().getIntExtra(EXTRA_PRODUCT_ID, 0);
        if (productId <= 0) {
            finish();
            return;
        }
        ordersManager = new OrdersManager(this, sessionManager.getToken());

        titleInput = findViewById(R.id.productTitleInput);
        originalInput = findViewById(R.id.productOriginalInput);
        discountInput = findViewById(R.id.productDiscountInput);
        daysInput = findViewById(R.id.productDaysInput);
        saveButton = findViewById(R.id.saveButton);
        loadMessage = findViewById(R.id.loadMessage);
        formGroup = findViewById(R.id.formGroup);

        String name = getIntent().getStringExtra(EXTRA_PRODUCT_NAME);
        ((TextView) findViewById(R.id.productNameHint)).setText(name == null ? "" : name);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        saveButton.setOnClickListener(v -> save());
        loadMessage.setOnClickListener(v -> {
            if (!loaded) loadProduct(); // "Tap to retry"
        });
        // Keyboard "Done" on the last field saves, same as tapping the button.
        daysInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                save();
                return true;
            }
            return false;
        });

        loadProduct();
    }

    // ---------------------------------------------------------------- Load

    private void loadProduct() {
        formGroup.setVisibility(View.GONE);
        loadMessage.setText("Loading…");
        loadMessage.setVisibility(View.VISIBLE);

        ordersManager.getProduct(productId, new ApiCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                try {
                    if (!response.getBoolean("success")) {
                        loadMessage.setText(response.optString("message", "Couldn't load the product")
                                + ". Tap to retry.");
                        return;
                    }
                    JSONObject p = response.getJSONObject("product");

                    // The server says whether delivery_days is a number column or free text.
                    daysIsNumber = response.optBoolean("delivery_days_is_number", true);
                    daysInput.setInputType(daysIsNumber
                            ? InputType.TYPE_CLASS_NUMBER
                            : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                    daysInput.setHint(daysIsNumber ? "e.g. 3" : "e.g. 3-5 days");
                    titleInput.setFilters(new android.text.InputFilter[]{
                            new android.text.InputFilter.LengthFilter(
                                    response.optInt("title_max_length", 255))});

                    titleInput.setText(Format.str(p, "title"));
                    originalInput.setText(plainNumber(Format.str(p, "original_amount")));
                    discountInput.setText(plainNumber(Format.str(p, "discounted_amount")));
                    daysInput.setText(Format.str(p, "delivery_days"));

                    loaded = true;
                    loadMessage.setVisibility(View.GONE);
                    formGroup.setVisibility(View.VISIBLE);
                } catch (JSONException e) {
                    loadMessage.setText("Unexpected response from server. Tap to retry.");
                }
            }

            @Override
            public void onError(String message) {
                if (handleSessionExpired(message)) return;
                loadMessage.setText("Couldn't reach server. Tap to retry.");
            }
        });
    }

    // "14100.00" -> "14100", "12.50" stays "12.50", "0.00" / "" -> "" (no discount = empty box)
    private static String plainNumber(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        try {
            BigDecimal value = new BigDecimal(raw);
            if (value.signum() == 0) return "";
            return raw.matches("\\d+\\.0+") ? raw.substring(0, raw.indexOf('.')) : raw;
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    // ---------------------------------------------------------------- Save

    private void save() {
        if (saving || !loaded) return;

        String title = titleInput.getText().toString().trim();
        String original = originalInput.getText().toString().trim();
        String discount = discountInput.getText().toString().trim();
        String days = daysInput.getText().toString().trim();

        if (title.isEmpty()) {
            titleInput.setError("Enter the product title");
            titleInput.requestFocus();
            return;
        }
        // 1500 or 1499.50 - digits and one dot only, so nothing is ever misread as another number.
        if (!original.matches("\\d{1,9}(\\.\\d{1,2})?") || new BigDecimal(original).signum() <= 0) {
            originalInput.setError("Enter a price like 1500 or 1499.50");
            originalInput.requestFocus();
            return;
        }
        if (!discount.isEmpty()) {
            if (!discount.matches("\\d{1,9}(\\.\\d{1,2})?")) {
                discountInput.setError("Enter a price like 1200 or 1199.50, or leave empty");
                discountInput.requestFocus();
                return;
            }
            BigDecimal d = new BigDecimal(discount);
            if (d.signum() > 0 && d.compareTo(new BigDecimal(original)) >= 0) {
                discountInput.setError("Must be lower than the original price");
                discountInput.requestFocus();
                return;
            }
        }
        if (days.isEmpty()) {
            daysInput.setError("Enter the delivery days");
            daysInput.requestFocus();
            return;
        }
        if (daysIsNumber && !days.matches("\\d{1,4}")) {
            daysInput.setError("Enter a whole number, like 3");
            daysInput.requestFocus();
            return;
        }

        setSaving(true);
        ordersManager.updateProduct(productId, title, original, discount, days, new ApiCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                setSaving(false);
                if (!response.optBoolean("success", false)) {
                    toast(response.optString("message", "Couldn't save the product"));
                    return;
                }
                JSONObject p = response.optJSONObject("product");
                Intent result = new Intent();
                result.putExtra(RESULT_PRODUCT_ID, productId);
                result.putExtra(RESULT_TITLE, p == null ? title : Format.str(p, "title"));
                result.putExtra(RESULT_PRICE, p == null ? original : Format.str(p, "price"));
                setResult(RESULT_OK, result); // Home updates this product's tile
                toast("Product updated");
                finish();
            }

            @Override
            public void onError(String message) {
                setSaving(false);
                if (handleSessionExpired(message)) return;
                toast("Couldn't save: " + message);
            }
        });
    }

    private void setSaving(boolean value) {
        saving = value;
        saveButton.setEnabled(!value);
        saveButton.setText(value ? "Saving…" : "Save changes");
    }

    // ---------------------------------------------------------------- Helpers

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // Returns true (and sends the user to the login screen) when the login token is no longer valid.
    private boolean handleSessionExpired(String message) {
        if (!OrdersManager.SESSION_EXPIRED.equals(message)) return false;
        sessionManager.clear();
        Toast.makeText(this, "Session expired. Please log in again.", Toast.LENGTH_LONG).show();
        goToLogin();
        return true;
    }

    private void goToLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
