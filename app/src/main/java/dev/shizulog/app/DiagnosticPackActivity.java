package dev.shizulog.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DiagnosticPackActivity extends AppCompatActivity {
    public static final String EXTRA_FILE = "file";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ProgressBar progress; private TextView state; private TextView info;
    private MaterialButton generate; private MaterialButton share;
    private File logFile; private File generatedZip;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_diagnostic_pack);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.diagnosticRoot), (view,insets)->{
            Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom); return insets; });
        MaterialToolbar toolbar=findViewById(R.id.diagnosticToolbar); toolbar.setNavigationIcon(R.drawable.ic_arrow_back_24); toolbar.setNavigationOnClickListener(v->finish());
        progress=findViewById(R.id.diagnosticProgress); state=findViewById(R.id.diagnosticState); info=findViewById(R.id.diagnosticInfo);
        generate=findViewById(R.id.diagnosticGenerate); share=findViewById(R.id.diagnosticShare);
        String path=getIntent().getStringExtra(EXTRA_FILE); if(path!=null){File f=new File(path); if(f.isFile()) logFile=f;}
        if(logFile==null){state.setText("没有可用的当前日志"); generate.setEnabled(false);} else {
            state.setText("准备生成诊断包"); info.setText("当前日志："+logFile.getName()+"\n大小："+humanSize(logFile.length())+"\n\n将打包原始日志、崩溃摘要、设备信息、目标 App 信息、UID/PID 与 Shizuku 状态。"); }
        share.setEnabled(false); generate.setOnClickListener(v->generatePack()); share.setOnClickListener(v->sharePack());
    }

    private void generatePack(){ if(logFile==null||!logFile.isFile()){toast("当前日志不存在");return;} generate.setEnabled(false);share.setEnabled(false);progress.setVisibility(View.VISIBLE);
        executor.execute(()->{ DiagnosticPackExporter.Result result=DiagnosticPackExporter.export(this,logFile,msg->runOnUiThread(()->state.setText(msg)));
            runOnUiThread(()->{progress.setVisibility(View.GONE);generate.setEnabled(true); if(!result.success){state.setText("生成失败");toast(result.error);return;}
                generatedZip=result.file;state.setText("诊断包已生成");info.setText("文件："+generatedZip.getName()+"\n大小："+humanSize(generatedZip.length())+"\n\n分享前请注意：原始 Logcat 可能包含敏感信息。");share.setEnabled(true);}); }); }

    private void sharePack(){ if(generatedZip==null||!generatedZip.isFile()){toast("请先生成诊断包");return;} try{
        Uri uri=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",generatedZip); Intent intent=new Intent(Intent.ACTION_SEND); intent.setType("application/zip"); intent.putExtra(Intent.EXTRA_STREAM,uri); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(Intent.createChooser(intent,"分享 ShizuLog 诊断包"));
        }catch(Exception e){toast("分享失败："+e.getMessage());}}

    private static String humanSize(long bytes){if(bytes<1024)return bytes+" B";double kb=bytes/1024.0;if(kb<1024)return String.format(Locale.US,"%.1f KB",kb);double mb=kb/1024.0;if(mb<1024)return String.format(Locale.US,"%.2f MB",mb);return String.format(Locale.US,"%.2f GB",mb/1024.0);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    @Override protected void onDestroy(){executor.shutdownNow();super.onDestroy();}
}
