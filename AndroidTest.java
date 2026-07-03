import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public class AndroidTest {
    
    private String message;
    private TextView textView;
    
    public AndroidTest(TextView tv) {
        this.textView = tv;
        this.message = "Hello Android!";
    }
    
    public void updateUI(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            String savedMsg = savedInstanceState.getString("message_key");
            if (savedMsg != null) {
                this.message = savedMsg;
            }
        }
        
        Log.d("AndroidTest", "Updating UI with: " + message);
        textView.setText(message);
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String msg) {
        if (msg != null && !msg.isEmpty()) {
            this.message = msg;
        } else {
            Log.w("AndroidTest", "Attempted to set empty/null message");
        }
    }
    
    public static String formatMessage(String prefix, int count) {
        StringBuilder sb = new StringBuilder();
        sb.append(prefix);
        sb.append(": ");
        sb.append(count);
        sb.append(" items");
        return sb.toString();
    }
}