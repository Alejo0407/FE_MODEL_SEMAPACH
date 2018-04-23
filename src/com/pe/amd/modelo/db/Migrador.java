package com.pe.amd.modelo.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.pe.amd.modelo.beans.BeanManager;
import com.pe.amd.modelo.beans.Cabdocumentos;
import com.pe.amd.modelo.beans.Contingencia;
import com.pe.amd.modelo.beans.Detdocumentos;

class Migrador {
	public List<Contingencia> migrarContingencia(Connection origen, String fecha, Connection connection) throws SQLException {	
		ResultSet tabla = null,detalle = null;
		ArrayList<Contingencia> lista = null;
		ArrayList<Contingencia> errores = null;
		boolean error = false ;
		try ( 
				Statement st1 = origen.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE
						,ResultSet.CONCUR_UPDATABLE);
				Statement st2 = origen.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE
						,ResultSet.CONCUR_UPDATABLE);
				Statement st3 = connection.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE
						,ResultSet.CONCUR_UPDATABLE);
				PreparedStatement pst = connection
						.prepareStatement(BeanManager.SQL_INSERT_CONTINGENCIA);		
			){
			String sql1 = "select * from cabpagos where convert(date,fechareg) = '"+fecha.substring(0,4)+"-"
					+fecha.substring(4,6)+"-"+fecha.substring(6)+"' and (tipodoc = '003' or tipodoc = '001' "
					+ "or tipodoc = '006' or tipodoc = '007') order by tipodoc,seriedoc,nrodoc ";
			String sql2 = "select * from pagos where convert(date,fechareg) = '"+fecha.substring(0,4)+"-"
					+fecha.substring(4,6)+"-"+fecha.substring(6)+"' and (tipodoc = '003' or tipodoc = '001' "
					+ "or tipodoc = '006' or tipodoc = '007') order by tipodoc,seriedoc,nrodoc ";
			String sql3 = "select * from sunat.contingencia where TRIM(fecemision) = \""+fecha+"\"";
			
			st3.executeQuery(sql3);
			if(st3.getResultSet().next()) {
				st3.getResultSet().close();
				st3.execute("delete from sunat.contingencia where TRIM(fecemision) = \""+fecha+"\"");
			}
			
			tabla = st1.executeQuery(sql1);
			detalle = st2.executeQuery(sql2);
			lista = new ArrayList<>();
			errores = new ArrayList<>();
			while(tabla.next()) {
				Contingencia cont = new Contingencia();
				cont.setPeriodo(fecha.substring(0, 6));//AAAAMMDD
				cont.setMotivo("7");
				cont.setFecemision(fecha);
				
				String tipodoc = tabla.getString("tipodoc");
				if(tipodoc.equalsIgnoreCase("003") || tipodoc.equalsIgnoreCase("006") 
						|| tipodoc.equalsIgnoreCase("007"))
					tipodoc = "03";			
				else
					tipodoc = "01";
				cont.setTipodocum(tipodoc);
				cont.setSerie(String.format("%04d", tabla.getInt("seriedoc")));
				cont.setNumero(tabla.getString("nrodoc"));
				
				if(tabla.getInt("anulado") != 1) {
					String auxiliar = tabla.getString("tipodoc")
							+tabla.getString("seriedoc")
							+tabla.getString("nrodoc");
					detalle.beforeFirst();
					double v_gravadas = 0.0,v_igv = 0,v_total = 0;
					while(detalle.next()) {
						if( (detalle.getString("tipodoc").trim()
							+detalle.getString("seriedoc").trim()
							+detalle.getString("nrodoc").trim()).equals(auxiliar) ) {
							v_gravadas += detalle.getDouble("impmesotros");
							v_igv += detalle.getDouble("impmesigv");
							v_total += detalle.getDouble("impmestotal");
						}
					}
					cont.setValventagra(v_gravadas);
					cont.setIgv(v_igv);
					cont.setTotalcomp(v_total);
				}
				
				//VERIFICACION DE ERRORES RUC/DNI
				boolean error_local = this.verificarError(tabla.getString("ruc"), tabla.getDouble("codcliente"));
				if(error_local) 
					cont.setTipodocucli("X");
				else {
					cont.setTipodocucli(tabla.getString("ruc"));
					if(cont.getTipodocucli()  == null)
						cont.setTipodocucli("1");
					else if(cont.getTipodocucli().equals("") || cont.getTipodocucli().equals(" "))
						cont.setTipodocucli("1");
					else if(cont.getTipodocucli().length() == 8)
						cont.setTipodocucli("1");
					else if(cont.getTipodocucli().length()  == 11)
						cont.setTipodocucli("6");
				}
				if(tabla.getString("ruc")== null)
					cont.setNumdocucli("00000000");
				else{
					if(!error_local && (tabla.getString("ruc").equals("")
							|| tabla.getString("ruc").equals(" ")))
						cont.setNumdocucli("00000000");
					else	
						cont.setNumdocucli(tabla.getString("ruc").trim());
				}
				cont.setNombrecli(tabla.getString("propietario").trim());
				BeanManager.insertContingencia(pst, cont);
				lista.add(cont);
				if(error_local) {
					error = true;
					errores.add(cont);
				}
			}
			tabla.close();
			detalle.close();
		}
		catch(SQLException e) {lista = null; throw e;}
		if(!error)
			return lista;
		else {
			String mensaje = "ERROR EN EL LA MIGRACION... DNI O RUC ERRONEO EN LOS SIGUIENTES DOCUMENTOS\n";
			
			for(Contingencia cab:errores) {
				mensaje += "-->" + cab.getSerie()+"-"+cab.getNumero();
				mensaje += "\n";
			}
			throw new SQLException (mensaje);
		}
	}

	public List<Cabdocumentos> migrarFacturas(Connection corigen, String fecha, boolean corregido,Connection connection)  throws SQLException{
		ArrayList<Cabdocumentos> lista = null;
		try (
				Statement st1 = connection.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE
						,ResultSet.CONCUR_UPDATABLE); 
				Statement st2 = corigen.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE
						,ResultSet.CONCUR_UPDATABLE); 
				Statement st3 = corigen.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE
						,ResultSet.CONCUR_UPDATABLE); 
			){
			//Primero se Borra las lineas cono homologado = -1 --> Rechazado por Excepcion
			//Se pregunta al usuario si ha corregido los errores (homologado = -2) --> Rechazado
			String msj = "";
			st1.executeQuery("SELECT transaccion FROM sunat.cabdocumentos "
					+ "WHERE tipodocumento = '01' "+ 
					(!corregido ?"AND homologado = -1 ;":"AND (homologado = -1 OR homologado = -2);"));
			while(st1.getResultSet().next()) 
				msj += st1.getResultSet().getString(1)+",";
			
			if(!msj.equals("")) {
				st1.execute("DELETE FROM sunat.cabdocumentos "
						+ "WHERE tipodocumento = '01' "+ 
						(corregido ?"AND homologado = -1 ;":"AND (homologado = -1 OR homologado = -2);"));
				st1.execute("DELETE FROM sunat.detdocumentos "
						+ "WHERE transaccion IN ("+msj.substring(0,msj.length()-1) + ");");
			}
			
			//Se extrae la informacion
			lista = new ArrayList<>();
			String not_into = "";
			
			st1.executeQuery("select * from sunat.cabdocumentos where TRIM(fechaemision)"
					+ " = \""+fecha+"\" and tipodocumento = '01';");
			
			while(st1.getResultSet().next()) {
				lista.add(BeanManager.getCabdocumentos(st1.getResultSet()));
				not_into +="'"+ lista.get(lista.size()-1).getTransaccion() + "',";
			}
			
			if(!not_into.isEmpty())
				not_into = " AND ( codusu+tipodoc+seriedoc+convert(varchar,nropago) ) NOT IN "
					+ "(" + not_into.substring(0, not_into.length()-1) +") ";
			String sql2 = "select * from cabpagos where convert(date,fechareg) = '"
					+fecha.substring(0, 4)+"-"+fecha.substring(4,6)+"-"+fecha.substring(6)
					+"' and tipodoc = '001'  and anulado <> 1 "
					+not_into+" order by tipodoc,seriedoc,nrodoc ";
			try(ResultSet tabla = st2.executeQuery(sql2)){
				if( tabla.last())
					System.out.println("Migrando...");
				tabla.beforeFirst();
				while(tabla.next()) {
					Cabdocumentos factura = new Cabdocumentos();
					factura.setTransaccion(tabla.getString("codusu").trim()
							+tabla.getString("tipodoc").trim()
							+tabla.getString("seriedoc").trim()
							+tabla.getString("nropago").trim());
					factura.setSerie(tabla.getString("seriedoc"));
					factura.setNumero(tabla.getString("nrodoc") == null?
							"0000":tabla.getString("nrodoc"));
					factura.setNumcliente(tabla.getString("ruc" != null?
							tabla.getString("ruc"):"00000000"));
					factura.setTipocliente(factura.getNumcliente().length() == 11?
							"6":"1");
					factura.setNomcliente(tabla.getString("propietarios") != null ?
							tabla.getString("propietario").trim():"-");
					factura.setValventaafe(tabla.getString("impsubtotal") == null?
							0.00:tabla.getDouble("impsubtotal"));
					factura.setIgv(tabla.getString("impigv") != null ?
							tabla.getDouble("impsubtotal"):0.00);
					factura.setTotaldoc(tabla.getString("imptotal") != null?
							tabla.getDouble("imptotal"):0.00);
					try(PreparedStatement  pst1 = connection.prepareStatement(BeanManager.SQL_INSERT_CABECERA)){
						BeanManager.insertCabecera(pst1, factura);
						System.out.println("Factura: " + factura.getTransaccion() 
						+ "::" + factura.getSerie() + "-" + factura.getNumero()
						+ "::" + factura.getTipocliente() + "::" + factura.getNomcliente()
						+ "::" + factura.getNumcliente()+ "::" + factura.getTotaldoc());
					}
					int sec = 0;
					try(ResultSet detalle = st3.executeQuery("select * from dbo.pagos "
							+ "where (codusu+tipodoc+seriedoc+convert(varchar,nropago)) = '"
							+factura.getTransaccion()+"'")) {
						while(detalle.next()) {
							Detdocumentos det = new Detdocumentos();
							det.setTransaccion(detalle.getString("codusu").trim()
									+detalle.getString("tipodoc").trim()
									+detalle.getString("seriedoc").trim()
									+detalle.getString("nropago").trim());
							det.setSec(String.format("%03d", ++sec));
							det.setDenominacion(detalle.getString("detalle") != null?
									detalle.getString("detalle"):"SIN DETALLE");
							det.setUnidad("UND");
							det.setCantidad(1.0);	
							det.setValunitario(detalle.getString("impmesotros") != null?
									detalle.getDouble("impmesotros"):0.00);
							det.setIgv(detalle.getString("impmesigv") != null?
									detalle.getDouble("impmesigv"):0.00);
							det.setValtotal(det.getValunitario()*det.getCantidad()+det.getIgv());
							
							try(PreparedStatement pst2 = connection.prepareStatement
									(BeanManager.SQL_INSERT_DETALLE)){
								BeanManager.insertDetalle(pst2, det);
							}
						}
					}
					lista.add(factura);
				}
			}
			System.out.println("Fin Migrando....");
		}catch(SQLException e) { lista = null; throw e;}
		return lista;
	}
	public List<Cabdocumentos> migrarBoletas(Connection corigen, String fecha, boolean corregido,Connection connection)  throws SQLException{
		return null;
	}
	
	private boolean verificarError(String xtdcliente, Double valor) {
		boolean erroneo = false;
		
		if(xtdcliente == null && valor  != 0.0)
			erroneo = false;
		else if(xtdcliente == null && valor  == 0.0){
			erroneo = true;
		}
		else if((xtdcliente.equals("") || xtdcliente.equals(" "))  && valor != 0.0)
			erroneo = false;
		else if((xtdcliente.equals("") || xtdcliente.equals(" "))  && valor == 0.0){
			erroneo = true;
		}
		else{
			if(xtdcliente.length() == 8 || xtdcliente.length()  == 11)
				erroneo = false;
			else{
				erroneo = true;
			}
		}
		return erroneo;
	}
}
