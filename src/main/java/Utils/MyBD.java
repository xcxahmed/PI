package Utils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class MyBD {
    private Connection conn;
    final private String URL="jdbc:mysql://localhost:3306/investia";
    final private String USER="root";
    final private String PASS="";
    private static MyBD instance;
    private MyBD(){
        try {
            conn=DriverManager.getConnection(URL,USER,PASS);
            System.out.println("Connected");
        }catch (SQLException s){
            System.out.println(s.getMessage());
        }
    }
    public static MyBD getInstance(){
        if(instance==null){
            instance=new MyBD();
        }
        return instance;
    }

    public Connection getConn() {
        return conn;
    }
}
