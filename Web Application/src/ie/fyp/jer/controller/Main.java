package ie.fyp.jer.controller;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import javax.annotation.Resource;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.apache.http.HttpEntity;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import eu.bitwalker.useragentutils.UserAgent;
import ie.fyp.jer.domain.Logged;
import ie.fyp.jer.domain.MobileResponse;
import ie.fyp.jer.config.LogCookie;
import ie.fyp.jer.domain.HouseDash;

/**
 * Servlet implementation class Main
 */
@WebServlet("/index.jsp")
public class Main extends HttpServlet {
	private static final long serialVersionUID = 1L;
	@Resource(name="jdbc/aws-rds")
	private DataSource dataSource;

	/**
	 * @see HttpServlet#HttpServlet()
	 */
	public Main() {
		super();
	}

	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		Cookie cookie = checkCookie(request.getCookies());
		if(request.getSession().getAttribute("logged")!=null) {
			request.setAttribute("website", "IoT Efficiency");
			request.setAttribute("main", "main");
			request.setAttribute("subtitle", "Dashboard");
			int log = ((Logged)request.getSession().getAttribute("logged")).getId();
			setAccount(log, request);
			setLastLog(log, request);
			setHouse(log, request);
			request.getRequestDispatcher("/WEB-INF/index.jsp").forward(request, response);
		}
		else if(cookie!=null) {
			String ip = request.getRemoteAddr();
			String user = request.getHeader("User-Agent");
			Logged log = LoginCookies(cookie, ip, user, response);
			if(log!=null) {
				request.getSession().setAttribute("logged", log);
				if(request.getParameter("startup")!=null) {
					int code = request.getSession().getAttribute("logged")!=null ? 1 : 0;
					MobileResponse mResponse = new MobileResponse(log, code);
					response.getWriter().write(new Gson().toJson(mResponse));
				}
				else {
					String next = request.getParameter("path");
					if(next==null)
						next="";
					response.sendRedirect(next);
				}
			}
			else
				request.getRequestDispatcher("/WEB-INF/homepage.jsp").forward(request, response); 
		}
		else
			request.getRequestDispatcher("/WEB-INF/homepage.jsp").forward(request, response);
	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doGet(request, response);
	}

	private Cookie checkCookie(Cookie cookies[]) {
		if(cookies!=null)
			for(int i=0; i<cookies.length; i++)
				if(cookies[i].getName().equals("login"))
					return cookies[i];
		return null;
	}

	private Logged LoginCookies(Cookie cookie, String ip, String user, HttpServletResponse response) {
		Logged log = null;
		String sql = "SELECT a.email, a.id, l.device " + 
				"FROM FYP.Account a " + 
				"JOIN FYP.Login l " + 
				"ON l.accountId = a.id " + 
				"WHERE l.cookie = ? " + 
				"AND l.expire > ?" +
				"AND l.type != ?;";
		Object val[] = {cookie.getValue(), System.currentTimeMillis(), "Login Attempt"};
		try (Connection con = dataSource.getConnection();
				PreparedStatement ptst = prepare(con, sql, val);
				ResultSet rs = ptst.executeQuery()) {
			if(rs.next()) {
				log = new Logged(rs.getString(1), rs.getInt(2));
				log.setBuildings(setHouses(con, log.getId()));
				log.setType(rs.getString(3));
				setLogin(con, ip, rs.getString(3), log.getId(), user, response);
			}
		} catch (SQLException e) {
			e.printStackTrace();
		} catch (ClientProtocolException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return log;
	}
	
	private void setLogin(Connection con, String ip, String type, int id, String user, HttpServletResponse httpResponse) throws ClientProtocolException, IOException, SQLException {
		CloseableHttpClient httpclient = HttpClients.createDefault();
		HttpGet httpGet = new HttpGet("http://ip-api.com/json/" + ip);
		CloseableHttpResponse response = httpclient.execute(httpGet);
		String location = "Unknown";
		try {
			HttpEntity entity = response.getEntity();
			String gson = EntityUtils.toString(entity);
			JsonParser parse = new JsonParser();
			JsonObject object = parse.parse(gson).getAsJsonObject();
			location = (object.get("city").getAsString() + " " + object.get("countryCode").getAsString());
			EntityUtils.consume(entity);
		} catch (Exception e) {
			System.out.println("GSON error occured in login controller - IP is likely local.");
		} finally {
			response.close();
		}
		Long expire = System.currentTimeMillis() + ((long)1000 * 60 * 60 * 24 * 30);
		String device = type;
		String cookie = LogCookie.generate();
		Object val3[] = {id, System.currentTimeMillis(), location, user, device, cookie, expire, "Session"};
		String sql = "INSERT INTO FYP.Login(accountid, datetime, location, osbrowser, device,"
				+ "cookie, expire, type)VALUES (?, ?, ?, ?, ?, ?, ?, ?);";
		try (PreparedStatement ptst = prepare(con, sql, val3)) {
			if(ptst.executeUpdate()==1)
				httpResponse.addCookie(createCookie("login", cookie, 60*60*24*30));
		}
	}
	
	private Cookie createCookie(String name, String details, int life) {
		Cookie temp = new Cookie(name, details);
		temp.setMaxAge(life);
		return temp;
	}

	private ArrayList<String> setHouses(Connection con, int id) throws SQLException {
		ArrayList<String> houses = new ArrayList<>();
		String sql = "SELECT name FROM FYP.building WHERE accountId = ?";
		Object val2[] = {id};
		try (PreparedStatement ptst1 = prepare(con, sql, val2);
				ResultSet rs1 = ptst1.executeQuery()) {
			while(rs1.next())
				houses.add(rs1.getString(1));
		}
		return houses;	
	}

	private void setAccount(int log, HttpServletRequest request) {
		String sql ="SELECT a.*, p.date, COUNT(b.name) " + 
				"FROM FYP.Account a " + 
				"LEFT JOIN FYP.Building b ON b.accountId = a.id " + 
				"JOIN FYP.Password p ON p.accountId = a.id " +
				"WHERE a.id = ? " + 
				"GROUP BY a.id, p.date " + 
				"ORDER BY p.date DESC " + 
				"LIMIT 1;";
		Object val[] = {log};
		try (Connection con = dataSource.getConnection();
				PreparedStatement ptst = prepare(con, sql, val);
				ResultSet rs = ptst.executeQuery()) {
			if(rs.next()) {
				request.setAttribute("firstName", rs.getString(2));
				request.setAttribute("lastName", rs.getString(3));
				request.setAttribute("email", rs.getString(4));
				String phone = rs.getString(5);
				if(phone.equals(""))
					phone = "Not Set";
				request.setAttribute("phone", phone);
				String street = rs.getString(6);
				if(street.equals(""))
					street = "Not Set";
				request.setAttribute("street", street);
				request.setAttribute("town", rs.getString(7));
				request.setAttribute("county", rs.getString(8));
				request.setAttribute("regDate", getDate("dd-MMM-yyyy hh:mm:ss", rs.getLong(9)));
				if(rs.getLong(9)==rs.getLong(11))
					request.setAttribute("lastPas", "Never Changed");
				else
					request.setAttribute("lastPas", getDate("dd-MMM-yyyy hh:mm:ss", rs.getLong(11)));
				request.setAttribute("houses", rs.getInt(12));
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	private void setLastLog(int log, HttpServletRequest request) {
		String sql = "SELECT dateTime, location, osBrowser, device " + 
				"FROM FYP.Login " + 
				"WHERE dateTime IN(SELECT MAX(dateTime) " + 
				"				FROM FYP.Login " + 
				"				WHERE accountId = ? " + 
				"				AND type = ?);";
		Object val[] = {log, "Login"};
		try (Connection con = dataSource.getConnection();
				PreparedStatement ptst = prepare(con, sql, val);
				ResultSet rs = ptst.executeQuery()) {
			if(rs.next()) {
				request.setAttribute("prev", true);
				request.setAttribute("lastLog", getDate("dd-MMM-yyyy hh:mm:ss", rs.getLong(1)));
				request.setAttribute("location", rs.getString(2));
				request.setAttribute("system", getSystem(rs.getString(3)));
				if(rs.getString(4).equals("mobile")) {
					request.setAttribute("system", getOS(rs.getString(3)) + " - Mobile Application");
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	private void setHouse(int log, HttpServletRequest request) {
		ArrayList<HouseDash> houseList = new ArrayList<>();
		String sql = "SELECT name, location, id " + 
				"FROM FYP.building " + 
				"WHERE accountId = ?";
		Object val[] = {log};
		try (Connection con = dataSource.getConnection();
				PreparedStatement ptst = prepare(con, sql, val);
				ResultSet rs = ptst.executeQuery()) {
			while(rs.next()) {
				HouseDash temp = new HouseDash(rs.getString(1), rs.getString(2));
				sql = "SELECT name, floor " + 
						"FROM FYP.Room " + 
						"WHERE buildingId = ?";
				Object val1[] = {rs.getInt(3)};
				try (PreparedStatement ptst1 = prepare(con, sql, val1);
						ResultSet rs1 = ptst1.executeQuery()) {
					while(rs1.next()) {
						temp.addRoom(rs1.getString(1), rs1.getInt(2));
					}
				}
				houseList.add(temp);
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		request.setAttribute("houseDash", houseList);
	}

	private PreparedStatement prepare(Connection con, String sql, Object values[]) throws SQLException {
		final PreparedStatement ptst = con.prepareStatement(sql);
		for (int i = 0; i < values.length; i++) {
			ptst.setObject(i+1, values[i]);
		}
		return ptst;
	}


	private String getSystem(String details) {
		return getOS(details) + " - " + getBrowser(details);
	}

	private String getOS(String details) {
		UserAgent user = UserAgent.parseUserAgentString(details);
		return user.getOperatingSystem().getName();
	}

	private String getBrowser(String details) {
		UserAgent user = UserAgent.parseUserAgentString(details);
		return user.getBrowser().getName();
	}

	private String getDate(String format, long date) {		
		SimpleDateFormat df= new SimpleDateFormat(format);
		return df.format(new Date(date));
	}
}